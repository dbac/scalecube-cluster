package io.scalecube.cluster.leaderelection;

import io.scalecube.cluster.Cluster;
import io.scalecube.cluster.Member;
import io.scalecube.cluster.leaderelection.api.ElectionEvent;
import io.scalecube.cluster.leaderelection.api.ElectionTopic;
import io.scalecube.cluster.leaderelection.api.HeartbeatRequest;
import io.scalecube.cluster.leaderelection.api.HeartbeatResponse;
import io.scalecube.cluster.leaderelection.api.Leader;
import io.scalecube.cluster.leaderelection.api.VoteRequest;
import io.scalecube.cluster.leaderelection.api.VoteResponse;
import io.scalecube.transport.Message;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RaftLeaderElection implements ElectionTopic {

  private static final Logger LOGGER = LoggerFactory.getLogger(RaftLeaderElection.class);

  public static final String LEADER_ELECTION = "leader-election";

  private final RaftStateMachine stateMachine;

  private final DirectProcessor<ElectionEvent> processor = DirectProcessor.create();

  private final String topic;

  private Cluster cluster;

  private String memberId;

  @Override
  public String id() {
    return this.cluster.member().id();
  }

  @Override
  public String name() {
    return this.topic;
  }

  public State currentState() {
    return stateMachine.currentState();
  }

  public Mono<Leader> leader() {
    return Mono.just(new Leader(this.memberId, stateMachine.leaderId()));
  }

  public RaftLeaderElection(Cluster cluser, String topic, Config config) {
    this.topic = topic;
    this.cluster = cluser;
    int timeout = config.timeout();
    this.stateMachine =
        RaftStateMachine.builder()
            .id(this.cluster.member().id())
            .timeout(timeout)
            .heartbeatInterval(config.heartbeatInterval())
            .heartbeatSender(sendHeartbeat(Duration.ofMillis(config.timeout())))
            .build();

    this.stateMachine.onFollower(
        asFollower -> {
          this.processor.onNext(ElectionEvent.follower());
        });

    this.stateMachine.onCandidate(
        asCandidate -> {
          this.stateMachine.nextTerm();
          this.processor.onNext(ElectionEvent.candidate());

          startElection(Duration.ofMillis(config.electionTimeout() + config.timeout()))
              .timeout(Duration.ofMillis(config.electionTimeout()))
              .subscribe(
                  result -> {
                    if (result) {
                      LOGGER.info(
                          "[{}:{}] granted votes and transition to leader",
                          this.memberId,
                          stateMachine.currentState());
                      stateMachine.becomeLeader(stateMachine.currentTerm().getLong());
                    } else {
                      LOGGER.info(
                          "[{}:{}] not granted with votes and transition to follower",
                          this.memberId,
                          stateMachine.currentState());
                      stateMachine.becomeFollower(stateMachine.currentTerm().getLong());
                    }
                  },
                  error -> {
                    LOGGER.info(
                        "[{}:{}] didnt recive votes due to timeout will become follower.",
                        this.memberId,
                        stateMachine.currentState());
                    stateMachine.becomeFollower(stateMachine.currentTerm().getLong());
                  });
        });

    this.stateMachine.onLeader(
        asLeader -> {
          this.processor.onNext(ElectionEvent.leader());
        });

    this.stateMachine.becomeFollower(this.stateMachine.nextTerm());
  }

  @Override
  public Flux<ElectionEvent> listen() {
    return processor;
  }

  public RaftLeaderElection(Cluster cluster, String name) {
    this(cluster, name, Config.build());
    this.cluster = cluster;
    this.cluster.updateMetadataProperty(name, LEADER_ELECTION).subscribe();
  }

  public Mono<Void> start() {

    cluster
        .listen()
        .subscribe(
            request -> {
              if (Protocol.isVote(topic, request.qualifier())) {
                onVoteRequested(request)
                    .subscribe(
                        resp -> {
                          cluster.send(request.sender(), resp).subscribe();
                        });
              } else if (Protocol.isHeartbeat(topic, request.qualifier())) {
                onHeartbeatRecived(request)
                    .subscribe(
                        resp -> {
                          cluster.send(request.sender(), resp).subscribe();
                        });
              }
            });

    return Mono.create(
        sink -> {
          this.memberId = cluster.member().id();
          this.stateMachine.becomeFollower(this.stateMachine.currentTerm().getLong());
          sink.success();
        });
  }

  private Mono<Message> onHeartbeatRecived(Message request) {
    return Mono.create(
        sink -> {
          HeartbeatRequest data = request.data();
          LOGGER.trace(
              "[{}:{}] recived heartbeat request: [{}]",
              this.memberId,
              stateMachine.currentState(),
              data);
          stateMachine.heartbeat(data.memberId(), data.term());
          sink.success(heartbeatResponseMessage(request));
        });
  }

  private Mono<Message> onVoteRequested(Message request) {
    VoteRequest voteReq = request.data();

    boolean voteGranted =
        stateMachine.currentTerm().isBefore(voteReq.term())
            && currentState().equals(State.FOLLOWER);

    LOGGER.info(
        "[{}:{}] recived vote request: [{}] voteGranted: [{}].",
        this.memberId,
        stateMachine.currentState(),
        request.data(),
        voteGranted);

    if (stateMachine.isCandidate()) {
      stateMachine.becomeFollower(voteReq.term());
    }

    return Mono.just(voteResponseMessage(request, voteGranted));
  }

  /**
   * find all leader election services that are remote and send current term to all of them.
   *
   * @return consumer.
   */
  private Consumer sendHeartbeat(Duration timeout) {
    return heartbeat -> {
      findPeers()
          .stream()
          .forEach(
              instance -> {
                LOGGER.trace(
                    "member: [{}:{}] sending heartbeat: [{}].",
                    this.memberId,
                    currentState(),
                    instance.id());
                cluster
                    .requestResponse(instance.address(), heartbeatRequest())
                    .timeout(timeout)
                    .subscribe(
                        next -> {
                          HeartbeatResponse response = next.data();
                          stateMachine.updateTerm(response.term());
                        });
              });
    };
  }

  // election requesting votes from peers and try reach consensus.
  // if most of peer members grant vote then member transition to a leader state.
  // the election process is waiting until timeout of consensusTimeout in case
  // timeout reached the member transition to Follower state.
  private Mono<Boolean> startElection(Duration timeout) {
    Collection<Member> peers = findPeers();
    if (!peers.isEmpty()) {
      System.out.println(">>> peers [" + peers.size() + "] " + (peers.size() / 2));
      return Flux.fromStream(peers.stream())
          .concatMap(
              member -> cluster.requestResponse(member.address(), voteRequest()).timeout(timeout))
          .map(result -> ((VoteResponse) result.data()).granted())
          .filter(vote -> vote)
          .take((1 + peers.size()) / 2)
          .reduce((a, b) -> a && b);
    } else {
      return Mono.just(true);
    }
  }

  private Message heartbeatRequest() {
    return Protocol.asHeartbeatRequest(
        this.cluster.address(),
        topic,
        new HeartbeatRequest(stateMachine.currentTerm().getLong(), this.memberId));
  }

  private Message heartbeatResponseMessage(Message request) {

    return Message.builder()
        .sender(this.cluster.address())
        .correlationId(request.correlationId())
        .data(new HeartbeatResponse(this.memberId, stateMachine.currentTerm().getLong()))
        .build();
  }

  private Message voteRequest() {
    return Protocol.asVoteRequest(
        this.cluster.address(), topic, new VoteRequest(stateMachine.currentTerm().getLong()));
  }

  private Message voteResponseMessage(Message request, boolean voteGranted) {
    return Message.builder()
        .sender(this.cluster.address())
        .correlationId(request.correlationId())
        .data(new VoteResponse(voteGranted, this.memberId))
        .build();
  }

  private Collection<Member> findPeers() {
    return cluster
        .otherMembers()
        .stream()
        .filter(m -> cluster.metadata(m).containsKey(topic))
        .filter(m -> cluster.metadata(m).get(topic).equals(LEADER_ELECTION))
        .collect(Collectors.toSet());
  }
}