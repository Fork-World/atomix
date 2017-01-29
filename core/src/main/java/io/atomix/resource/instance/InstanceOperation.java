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

import io.atomix.catalyst.buffer.BufferInput;
import io.atomix.catalyst.buffer.BufferOutput;
import io.atomix.catalyst.serializer.CatalystSerializable;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.copycat.Operation;
import io.atomix.resource.Resource;

/**
 * Instance-level resource operation.
 * <p>
 * Instance operations are submitted by {@link Resource} instances to a specific state machine
 * in the Atomix cluster. The operation {@link #resource()} identifies the state machine to which
 * the operation is being submitted.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class InstanceOperation<T extends Operation<U>, U> implements Operation<U>, CatalystSerializable {
  protected long resource;
  protected T operation;

  protected InstanceOperation() {
  }

  protected InstanceOperation(long resource, T operation) {
    this.resource = resource;
    this.operation = operation;
  }

  /**
   * Returns the resource ID.
   *
   * @return The resource ID.
   */
  public long resource() {
    return resource;
  }

  /**
   * Returns the resource operation.
   *
   * @return The resource operation.
   */
  public T operation() {
    return operation;
  }

  @Override
  public void writeObject(BufferOutput<?> buffer, Serializer serializer) {
    buffer.writeLong(resource);
    serializer.writeObject(operation, buffer);
  }

  @Override
  public void readObject(BufferInput<?> buffer, Serializer serializer) {
    resource = buffer.readLong();
    operation = serializer.readObject(buffer);
  }

  @Override
  public String toString() {
    return String.format("%s[resource=%s, operation=%s]", getClass().getSimpleName(), resource, operation.getClass().getSimpleName());
  }

}
