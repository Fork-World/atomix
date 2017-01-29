/*
 * Copyright 2015 the original author or authors.
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
 * limitations under the License.
 */
package io.atomix.resource.instance;

import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.concurrent.Listener;
import io.atomix.catalyst.concurrent.ThreadContext;
import io.atomix.copycat.session.Session;
import io.atomix.resource.Resource;
import io.atomix.resource.ResourceStateMachine;

import java.util.function.Consumer;

/**
 * Instance-level resource session.
 * <p>
 * The instance session provides a {@link Session} implementation for a client-side {@link Resource}
 * instance. The session is associated with a specific open instance associated with a specific
 * server-side replicated {@link ResourceStateMachine} instance.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public final class InstanceSession implements Session {
  private final long resource;
  private final Session parent;
  private final ThreadContext context;

  /**
   * @throws NullPointerException if {@code parent} or {@code context} are null
   */
  public InstanceSession(long resource, Session parent, ThreadContext context) {
    this.resource = resource;
    this.parent = Assert.notNull(parent, "parent");
    this.context = Assert.notNull(context, "context");
  }

  @Override
  public long id() {
    return parent.id();
  }

  @Override
  public State state() {
    return parent.state();
  }

  @Override
  public Listener<State> onStateChange(Consumer<State> callback) {
    return parent.onStateChange(callback);
  }

  @Override
  public int hashCode() {
    return parent.hashCode();
  }

  @Override
  public boolean equals(Object object) {
    return parent.equals(object);
  }

  @Override
  public String toString() {
    return String.format("%s[id=%d, resource=%d]", getClass().getSimpleName(), id(), resource);
  }

}
