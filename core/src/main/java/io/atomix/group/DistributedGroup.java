/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.group;

import io.atomix.catalyst.annotations.Beta;
import io.atomix.catalyst.concurrent.Listener;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.group.election.Election;
import io.atomix.group.election.Term;
import io.atomix.group.messaging.Message;
import io.atomix.group.messaging.MessageClient;
import io.atomix.group.messaging.MessageService;
import io.atomix.resource.Resource;
import io.atomix.resource.ResourceTypeInfo;

import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Generic group abstraction for managing group membership, service discovery, leader election, and remote
 * scheduling and execution.
 * <p>
 * The distributed group resource facilitates managing group membership within an Atomix cluster. Membership is
 * managed by nodes {@link #join() joining} and {@link LocalMember#leave() leaving} the group, and instances
 * of the group throughout the cluster are notified on changes to the structure of the group. Groups can elect a
 * leader, and members can communicate directly with one another or through persistent queues.
 * <p>
 * Groups membership is managed in a replicated state machine. When a member joins the group, the join request
 * is replicated, the member is added to the group, and the state machine notifies instances of the
 * {@code DistributedGroup} of the membership change. In the event that a group instance becomes disconnected from
 * the cluster and its session times out, the replicated state machine will automatically remove the member
 * from the group and notify the remaining instances of the group of the membership change.
 * <p>
 * To create a membership group resource, use the {@code DistributedGroup} class or constructor:
 * <pre>
 *   {@code
 *   atomix.getGroup("my-group").thenAccept(group -> {
 *     ...
 *   });
 *   }
 * </pre>
 * <h2>Joining the group</h2>
 * When a new instance of the resource is created, it is initialized with an empty {@link #members()} list
 * as it is not yet a member of the group. Once the instance has been created, the user must join the group
 * via {@link #join()}:
 * <pre>
 *   {@code
 *   group.join().thenAccept(member -> {
 *     System.out.println("Joined with member ID: " + member.id());
 *   });
 *   }
 * </pre>
 * Once the group has been joined, the {@link #members()} list provides an up-to-date view of the group which will
 * be automatically updated as members join and leave the group. To be explicitly notified when a member joins or
 * leaves the group, use the {@link #onJoin(Consumer)} or {@link #onLeave(Consumer)} event consumers respectively:
 * <pre>
 *   {@code
 *   group.onJoin(member -> {
 *     System.out.println(member.id() + " joined the group!");
 *   });
 *   }
 * </pre>
 * <h2>Listing the members in the group</h2>
 * Users of the distributed group do not have to join the group to interact with it. For instance, while a server
 * may participate in the group by joining it, a client may interact with the group just to get a list of available
 * members. To access the list of group members, use the {@link #members()} getter:
 * <pre>
 *   {@code
 *   DistributedGroup group = atomix.getGroup("foo").get();
 *   for (GroupMember member : group.members()) {
 *     ...
 *   }
 *   }
 * </pre>
 * Once the group instance has been created, the group membership will be automatically updated each time the structure
 * of the group changes. However, in the event that the client becomes disconnected from the cluster, it may not receive
 * notifications of changes in the group structure.
 * <h2>Persistent members</h2>
 * {@code DistributedGroup} supports a concept of persistent members that requires members to <em>explicitly</em>
 * {@link LocalMember#leave() leave} the group to be removed from it. Persistent member {@link Message tasks} will remain
 * in a failed member's queue until the member recovers.
 * <p>
 * In order to support recovery, persistent members must be configured with a user-provided {@link GroupMember#id() member ID}.
 * The member ID is provided when the member {@link #join(String) joins} the group, and providing a member ID is
 * all that's required to create a persistent member.
 * <pre>
 *   {@code
 *   DistributedGroup group = atomix.getGroup("persistent-members").get();
 *   LocalGroupMember memberA = group.join("a").get();
 *   LocalGroupMember memberB = group.join("b").get();
 *   }
 * </pre>
 * Persistent members are not limited to a single node. If a node crashes, any persistent members that existed
 * on that node may rejoin the group on any other node. Persistent members rejoin simply by calling {@link #join(String)}
 * with the unique member ID. Once a persistent member has rejoined the group, its session will be updated and any
 * tasks remaining in the member's {@link MessageService} will be published to the member.
 * <p>
 * Persistent member state is retained <em>only</em> inside the group's replicated state machine and not on clients.
 * From the perspective of {@code DistributedGroup} instances in a cluster, in the event that the node on which
 * a persistent member is running fails, the member will {@link #onLeave(Consumer) leave} the group. Once the persistent
 * member rejoins the group, {@link #onJoin(Consumer)} will be called again on each group instance in the cluster.
 * <h2>Leader election</h2>
 * The {@code DistributedGroup} resource facilitates leader election which can be used to coordinate a group by
 * ensuring only a single member of the group performs some set of operations at any given time. Leader election
 * is a core concept of membership groups, and because leader election is a low-overhead process, leaders are
 * elected for each group automatically.
 * <p>
 * Leaders are elected using a fair policy. The first member to {@link #join() join} a group will always become the
 * initial group leader. Each unique leader in a group is associated with a {@link Election#term() term}. The term
 * represents a globally unique, monotonically increasing token that can be used for fencing. Users can listen for
 * changes in group terms and leaders with event listeners:
 * <pre>
 *   {@code
 *   DistributedGroup group = atomix.getGroup("election-group").get();
 *   group.election().onElection(term -> {
 *     ...
 *   });
 *   }
 * </pre>
 * The {@link Term#term() term} is guaranteed to be unique for each {@link Term#leader() leader} and is
 * guaranteed to be monotonically increasing. Each instance of a group is guaranteed to see the same leader for the
 * same term, and no two leaders can ever exist in the same term. In that sense, the terminology and constraints of
 * leader election in Atomix borrow heavily from the Raft consensus algorithm that underlies it.
 * <h2>Messaging</h2>
 * Members of a group and group instances can communicate with one another through the messaging API,
 * {@link MessageService}. Direct messaging between group members is reliable and is done as writes to the Atomix cluster.
 * Messages are held in memory within the Atomix cluster and are published to consumers using Copycat's session event
 * framework. Messages are guaranteed to be delivered to consumers in the order in which they were sent by a producer.
 * Because each message is dependent on at least one or more writes to the Atomix cluster, messaging is not intended
 * to support high-throughput use cases. Group messaging is designed for coordinating group behaviors. For example,
 * a leader can instruct a random member to perform a task through the messaging API.
 * <h3>Direct messaging</h3>
 * To send messages directly to a specific member of the group, use the associated {@link GroupMember}'s
 * {@link MessageClient}.
 * <pre>
 *   {@code
 *   GroupMember member = group.member("foo");
 *   MessageProducer<String> producer = member.messaging().producer("bar");
 *   producer.send("baz").thenRun(() -> {
 *     // Message acknowledged
 *   });
 *   }
 * </pre>
 * Users can specify the criteria by which a producer determines when a message is completed by configuring the
 * producer's {@link io.atomix.group.messaging.MessageProducer.Execution Execution} policy. To configure the execution
 * policy, pass {@link io.atomix.group.messaging.MessageProducer.Options MessageProducer.Options} when creating a
 * {@link io.atomix.group.messaging.MessageProducer}.
 * <pre>
 *   {@code
 *   MessageProducer.Options options = new MessageProducer.Options()
 *     .withExecution(MessageProducer.Execution.SYNC);
 *   MessageProducer<String> producer = member.messaging().producer("bar", options);
 *   }
 * </pre>
 * Producers can be configured to send messages using three execution policies:
 * <ul>
 *   <li>{@link io.atomix.group.messaging.MessageProducer.Execution#SYNC SYNC} sends messages to consumers
 *   and awaits acknowledgement from the consumer side of the queue. If a producer is producing to an entire group,
 *   synchronous producers will await acknowledgement from all members of the group.</li>
 *   <li>{@link io.atomix.group.messaging.MessageProducer.Execution#ASYNC ASYNC} awaits acknowledgement of
 *   persistence in the cluster but not acknowledgement that messages have been received and processed by consumers.</li>
 *   <li>{@link io.atomix.group.messaging.MessageProducer.Execution#REQUEST_REPLY REQUEST_REPLY} awaits
 *   arbitrary responses from all consumers to which a message is sent. If a message is sent to a group of consumers,
 *   message reply futures will be completed with a list of reply values.</li>
 * </ul>
 * When the {@link io.atomix.group.messaging.MessageProducer MessageProducer} is configured with the
 * {@link io.atomix.group.messaging.MessageProducer.Execution#ASYNC ASYNC} execution policy, the {@link CompletableFuture}
 * returned by the {@link io.atomix.group.messaging.MessageProducer#send(Object)} method will be completed as soon as
 * the message is persisted in the cluster.
 * <h3>Broadcast messaging</h3>
 * Groups also provide a group-wide {@link MessageClient} that allows users to broadcast messages to all members of a
 * group or send a direct message to a random member of a group. To use the group-wide message client, use the
 * {@link #messaging()} getter.
 * <pre>
 *   {@code
 *   MessageProducer<String> producer = group.messaging().producer("foo");
 *   producer.send("Hello world!").thenRun(() -> {
 *     // Message delivered to all group members
 *   });
 *   }
 * </pre>
 * By default, messages sent through the group-wide message producer will be sent to <em>all</em> members of the group.
 * But just as {@link io.atomix.group.messaging.MessageProducer.Execution Execution} policies can be used to define the
 * criteria by which message operations are completed, the {@link io.atomix.group.messaging.MessageProducer.Delivery Delivery}
 * policy can be used to define how messages are delivered when using a group-wide producer.
 * <pre>
 *   {@code
 *   MessageProducer.Options options = new MessageProducer.Options()
 *     .withDelivery(MessageProducer.Delivery.RANDOM);
 *   MessageProducer<String> producer = member.messaging().producer("bar", options);
 *   }
 * </pre>
 * Group-wide producers can be configured with the following {@link io.atomix.group.messaging.MessageProducer.Delivery Delivery}
 * policies:
 * <ul>
 *   <li>{@link io.atomix.group.messaging.MessageProducer.Delivery#RANDOM} producers send each message to a random
 *   member of the group. In the event that a message is not successfully {@link Message#ack() acknowledged} by a
 *   member and that member fails or leaves the group, random messages will be redelivered to remaining members
 *   of the group.</li>
 *   <li>{@link io.atomix.group.messaging.MessageProducer.Delivery#BROADCAST} producers send messages to all available
 *   members of a group. This option applies only to producers constructed from {@link io.atomix.group.DistributedGroup}
 *   messaging clients.</li>
 * </ul>
 * Delivery policies work in tandem with {@link io.atomix.group.messaging.MessageProducer.Execution Execution} policies
 * described above. For example, a group-wide producer configured with the
 * {@link io.atomix.group.messaging.MessageProducer.Execution#REQUEST_REPLY REQUEST_REPLY} execution policy and
 * the {@link io.atomix.group.messaging.MessageProducer.Delivery#BROADCAST BROADCAST} delivery policy will send each
 * message to all members of the group and aggregate replies into a {@link Collection} once all consumers have replied
 * to the message.
 * <p>
 * <h3>Message consumers</h3>
 * Messages delivered to a group member must be received by listeners registered on the {@link LocalMember}'s
 * {@link MessageService}. Only the node to which a member belongs can listen for messages sent to that member. Thus,
 * to listen for messages, join a group and create a {@link io.atomix.group.messaging.MessageConsumer}.
 * <pre>
 *   {@code
 *   LocalMember localMember = group.join().join();
 *   MessageConsumer<String> consumer = localMember.messaging().consumer("foo");
 *   consumer.onMessage(message -> {
 *     message.ack();
 *   });
 *   }
 * </pre>
 * When a message is received, consumers must always {@link Message#ack()} or {@link Message#reply(Object)} to the message.
 * Failure to ack or reply to a message will result in a memory leak in the cluster and failure to deliver any additional
 * messages to the consumer. When a consumer acknowledges a message, the message will be removed from memory in the cluster
 * and the producer that sent the message will be notified according to its configuration.
 * <h3>Persistent messaging</h3>
 * Messages sent directly to specific members of a group are typically delivered only while that member is connected to
 * the group. In the event that a member to which a message is sent fails, the message is failed. This can result in
 * transparent failures when using the {@link io.atomix.group.messaging.MessageProducer.Execution#ASYNC ASYNC} execution
 * policy. A message can be persisted but may never actually be delivered and acknowledged. To ensure that direct messages
 * are eventually delivered, persistent members must be used.
 * <pre>
 *   {@code
 *   LocalMember member = group.join("member-1").join();
 *   MessageConsumer<String> consumer = member.messaging().consumer("foo");
 *   consumer.onMessage(message -> {
 *     ...
 *   });
 *   }
 * </pre>
 * When a message is sent to a persistent member, the message will be persisted in the cluster until it can be delivered
 * to that member regardless of whether the member is actively connected to the cluster. If the persistent member crashes,
 * once the member rejoins the group pending messages will be delivered. Persistent members are also free to switch nodes
 * to rejoin the group on live nodes, and pending messages will still be redelivered.
 * <p>
 * Users must take care, however, when using persistent members. {@link io.atomix.group.messaging.MessageProducer.Delivery#BROADCAST BROADCAST}
 * messages sent to groups with persistent members that are not connected to the cluster will be persisted in memory in the
 * cluster until they can be delivered. If the producer that broadcasts the message is configured to await acknowledgement
 * or replies from members, producer {@link io.atomix.group.messaging.MessageProducer#send(Object) send} operations cannot
 * be completed until dead members rejoin the group.
 * <h3>Serialization</h3>
 * Users are responsible for ensuring the serializability of tasks, messages, and properties set on the group
 * and members of the group. Serialization is controlled by the group's {@link io.atomix.catalyst.serializer.Serializer}
 * which can be access via {@link #serializer()} or on the parent {@code Atomix} instance. Because objects are
 * typically replicated throughout the cluster, <em>it's critical that any object sent from any node should be
 * serializable by all other nodes</em>.
 * <p>
 * Users should register serializable types before performing any operations on the group.
 * <pre>
 *   {@code
 *   DistributedGroup group = atomix.getGroup("group").get();
 *   group.serializer().register(User.class, UserSerializer.class);
 *   }
 * </pre>
 * For the best performance from serialization, it is recommended that serializable types be registered with
 * unique type IDs. This allows the Catalyst {@link io.atomix.catalyst.serializer.Serializer} to identify the
 * type by its serialization ID rather than its class name. It's essential that the ID for a given type is
 * the same all all nodes in the cluster.
 * <pre>
 *   {@code
 *   group.serializer().register(User.class, 1, UserSerializer.class);
 *   }
 * </pre>
 * Users can also serialize {@link java.io.Serializable} types by simply registering the class without any
 * other serializer. Catalyst will attempt to use the optimal serializer based on the interfaces implemented
 * by the class. Alternatively, type registration can be disabled altogether via {@link Serializer#disableWhitelist()},
 * however this is not recommended as arbitrary deserialization of class names is slow and is a security risk.
 * <h3>Implementation</h3>
 * Group state is managed in a Copycat replicated {@link io.atomix.copycat.server.StateMachine}. When a
 * {@code DistributedGroup} is created, an instance of the group state machine is created on each replica in
 * the cluster. The state machine instance manages state for the specific membership group. When a member
 * {@link #join() joins} the group, a join request is sent to the cluster and logged and replicated before
 * being applied to the group state machine. Once the join request has been committed and applied to the
 * state machine, the group state is updated and existing group members are notified by
 * {@link io.atomix.copycat.server.session.ServerSession#publish(String, Object) publishing} state change
 * notifications to open instances of the group. Membership change event notifications are received by all
 * open instances of the resource.
 * <p>
 * Leader election is performed by the group state machine. When the first member joins the group, that
 * member will automatically be assigned as the group member. Each time an additional member joins the group,
 * the new member will be placed in a leader queue. In the event that the current group leader's
 * {@link io.atomix.copycat.session.Session} expires or is closed, the group state machine will assign a new
 * leader by pulling from the leader queue and will publish an {@code elect} event to all remaining group
 * members. Additionally, for each new leader of the group, the state machine will publish a {@code term} change
 * event, providing a globally unique, monotonically increasing token uniquely associated with the new leader.
 * <p>
 * To track group membership, the group state machine tracks the state of the {@link io.atomix.copycat.session.Session}
 * associated with each open instance of the group. In the event that the session expires or is closed, the group
 * member associated with that session will automatically be removed from the group and remaining instances
 * of the group will be notified.
 * <p>
 * The group state machine facilitates direct and broadcast messaging through writes to the Atomix cluster. Each message
 * sent to a group or a member of a group is committed as a single write to the cluster. Once persisted in the cluster,
 * messages are delivered to clients through the state machine's session events API. The group state machine delivers
 * messages to sessions based on the configured per-message delivery policy, and client-side group instances are responsible
 * for dispatching received messages to the appropriate consumers. When a consumer acknowledges or replies to a message,
 * another write is commited to the Atomix cluster, and the group state machine completes the associated message.
 * <p>
 * The group state machine manages compaction of the replicated log by tracking which state changes contribute to
 * the state of the group at any given time. For instance, when a member joins the group, the commit that added the
 * member to the group contributes to the group's state as long as the member remains a part of the group. Once the
 * member leaves the group or its session is expired, the commit that created and remove the member no longer contribute
 * to the group's state and are therefore released from the state machine and will be removed from the log during
 * compaction.
 *
 * @see GroupMember
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
@Beta
@ResourceTypeInfo(id=-20, factory=DistributedGroupFactory.class)
public interface DistributedGroup extends Resource<DistributedGroup> {

  /**
   * Configuration for cluster-wide {@link DistributedGroup}s.
   */
  class Config extends Resource.Config {
    public Config() {
    }

    public Config(Properties defaults) {
      super(defaults);
    }

    /**
     * Sets the duration after which to remove persistent members from the group.
     *
     * @param expiration The duration after which to remove persistent members from the group.
     * @return The group configuration.
     * @throws NullPointerException if the expiration is {@code null}
     */
    public Config withMemberExpiration(Duration expiration) {
      setProperty("expiration", String.valueOf(Assert.notNull(expiration, "expiration").toMillis()));
      return this;
    }
  }

  /**
   * Distributed group options.
   */
  class Options extends Resource.Options {
    private boolean autoRecover = true;

    public Options() {
    }

    public Options(Properties defaults) {
      super(defaults);
    }

    /**
     * Sets whether to automatically recover sessions and client-side state.
     *
     * @param autoRecover Whether to automatically recover sessions and client-side state.
     * @return The group options.
     */
    public Options withAutoRecover(boolean autoRecover) {
      setProperty("recover", String.valueOf(autoRecover));
      return this;
    }
  }

  /**
   * Returns the group election.
   * <p>
   * The returned election is specific to this group's set of members. The {@link Term} defined by the returned
   * election will not necessarily be reflected in any subgroups of this group.
   *
   * @return The group election.
   */
  Election election();

  /**
   * Returns the group message client.
   * <p>
   * The returned message client is group-wide and can be used to broadcast messages to all members of the group
   * or to random members of the group.
   *
   * @return The group message client.
   */
  MessageClient messaging();

  /**
   * Gets a group member by ID.
   * <p>
   * If the member with the given ID has not {@link #join() joined} the membership group, the resulting
   * {@link GroupMember} will be {@code null}.
   *
   * @param memberId The member ID for which to return a {@link GroupMember}.
   * @return The member with the given {@code memberId} or {@code null} if it is not a known member of the group.
   */
  GroupMember member(String memberId);

  /**
   * Gets the collection of all members in the group.
   * <p>
   * The group members are fetched from the cluster. If any {@link GroupMember} instances have been referenced
   * by this membership group instance, the same object will be returned for that member.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#join()} method to block the calling thread:
   * <pre>
   *   {@code
   *   Collection<GroupMember> members = group.members().get();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the lock is acquired in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   group.members().thenAccept(members -> {
   *     members.forEach(member -> {
   *       member.send("test", "Hello world!");
   *     });
   *   });
   *   }
   * </pre>
   *
   * @return The collection of all members in the group.
   */
  Collection<GroupMember> members();

  /**
   * Joins the instance to the membership group.
   * <p>
   * Joining the group results in a <em>new</em> member being created and joining the group. Each {@link DistributedGroup}
   * instance may represent multiple members of a group. The returned {@link CompletableFuture} will be completed
   * with the joined {@link LocalMember} object once the member has joined the group, but does not guarantee that
   * all other instances of the group have seen the newly joined member.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#join()} method to block the calling thread:
   * <pre>
   *   {@code
   *   group.join().join();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the lock is acquired in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   group.join().thenAccept(thisMember -> System.out.println("This member is: " + thisMember.id()));
   *   }
   * </pre>
   *
   * @return A completable future to be completed once the member has joined.
   */
  CompletableFuture<LocalMember> join();

  /**
   * Joins the instance to the membership group with a user-provided member ID.
   * <p>
   * Joining the group results in a <em>new</em> member being created and joining the group. Each {@link DistributedGroup}
   * instance may represent multiple members of a group. The returned {@link CompletableFuture} will be completed
   * with the joined {@link LocalMember} object once the member has joined the group, but does not guarantee that
   * all other instances of the group have seen the newly joined member.
   * <p>
   * When joining a group with a user-provided {@code memberId}, a persistent member is created. In the event that this
   * node crashes, the member may rejoin the group on any node with the same {@code memberId} and receive pending messages.
   * While the persistent member is disconnected from the cluster, it will not appear in the group {@link #members()}
   * list but its state will not be removed from the cluster.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#join()} method to block the calling thread:
   * <pre>
   *   {@code
   *   group.join("foo").join();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the lock is acquired in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   group.join("foo").thenAccept(thisMember -> System.out.println("This member is: " + thisMember.id()));
   *   }
   * </pre>
   *
   * @param memberId The unique member ID to assign to the member.
   * @return A completable future to be completed once the member has joined.
   */
  CompletableFuture<LocalMember> join(String memberId);

  /**
   * Joins the instance to the membership group with a user-provided member ID.
   * <p>
   * Joining the group results in a <em>new</em> member being created and joining the group. Each {@link DistributedGroup}
   * instance may represent multiple members of a group. The returned {@link CompletableFuture} will be completed
   * with the joined {@link LocalMember} object once the member has joined the group, but does not guarantee that
   * all other instances of the group have seen the newly joined member.
   * <p>
   * {@code metadata} provided when a persistent member joins a group can be viewed by all other instances of the
   * same group. Metadata objects mut be serializable either via Java's {@link java.io.Serializable} or by registering a
   * Catalyst {@link io.atomix.catalyst.serializer.TypeSerializer} on the group's {@link #serializer()}.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#join()} method to block the calling thread:
   * <pre>
   *   {@code
   *   group.join("foo").join();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the lock is acquired in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   group.join("foo").thenAccept(thisMember -> System.out.println("This member is: " + thisMember.id()));
   *   }
   * </pre>
   *
   * @param metadata Metadata to assign to the joined group member.
   * @return A completable future to be completed once the member has joined.
   */
  CompletableFuture<LocalMember> join(Object metadata);

  /**
   * Joins the instance to the membership group with a user-provided member ID.
   * <p>
   * Joining the group results in a <em>new</em> member being created and joining the group. Each {@link DistributedGroup}
   * instance may represent multiple members of a group. The returned {@link CompletableFuture} will be completed
   * with the joined {@link LocalMember} object once the member has joined the group, but does not guarantee that
   * all other instances of the group have seen the newly joined member.
   * <p>
   * When joining a group with a user-provided {@code memberId}, a persistent member is created. In the event that this
   * node crashes, the member may rejoin the group on any node with the same {@code memberId} and receive pending messages.
   * While the persistent member is disconnected from the cluster, it will not appear in the group {@link #members()}
   * list but its state will not be removed from the cluster.
   * <p>
   * {@code metadata} provided when a persistent member joins a group can be viewed by all other instances of the
   * same group. Metadata objects mut be serializable either via Java's {@link java.io.Serializable} or by registering a
   * Catalyst {@link io.atomix.catalyst.serializer.TypeSerializer} on the group's {@link #serializer()}.
   * <p>
   * This method returns a {@link CompletableFuture} which can be used to block until the operation completes
   * or to be notified in a separate thread once the operation completes. To block until the operation completes,
   * use the {@link CompletableFuture#join()} method to block the calling thread:
   * <pre>
   *   {@code
   *   group.join("foo", new MyMetadata()).join();
   *   }
   * </pre>
   * Alternatively, to execute the operation asynchronous and be notified once the lock is acquired in a different
   * thread, use one of the many completable future callbacks:
   * <pre>
   *   {@code
   *   group.join("foo", new MyMetadata()).thenAccept(thisMember -> System.out.println("This member is: " + thisMember.id()));
   *   }
   * </pre>
   *
   * @param memberId The unique member ID to assign to the member.
   * @param metadata Metadata to assign to the joined group member.
   * @return A completable future to be completed once the member has joined.
   */
  CompletableFuture<LocalMember> join(String memberId, Object metadata);

  /**
   * Adds a listener for members joining the group.
   * <p>
   * The provided {@link Consumer} will be called each time a member joins the group. Note that
   * the join consumer will be called before the joining member's {@link #join()} completes.
   * <p>
   * The returned {@link Listener} can be used to {@link Listener#close() unregister} the listener
   * when its use if finished.
   *
   * @param listener The join listener.
   * @return The listener context.
   */
  Listener<GroupMember> onJoin(Consumer<GroupMember> listener);

  /**
   * Removes the member with the given member ID from the group.
   *
   * @param memberId The member ID of the member to remove from the group.
   * @return A completable future to be completed once the member has been removed.
   */
  CompletableFuture<Void> remove(String memberId);

  /**
   * Adds a listener for members leaving the group.
   * <p>
   * The provided {@link Consumer} will be called each time a member leaves the group. Members can
   * leave the group either voluntarily or by crashing or otherwise becoming disconnected from the
   * cluster for longer than their session timeout. Note that the leave consumer will be called before
   * the leaving member's {@link LocalMember#leave()} completes.
   * <p>
   * The returned {@link Listener} can be used to {@link Listener#close() unregister} the listener
   * when its use if finished.
   *
   * @param listener The leave listener.
   * @return The listener context.
   */
  Listener<GroupMember> onLeave(Consumer<GroupMember> listener);

}
