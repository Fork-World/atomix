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
package io.atomix.atomics.util;

import io.atomix.catalyst.serializer.SerializableTypeResolver;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.resource.ResourceFactory;
import io.atomix.resource.ResourceStateMachine;
import io.atomix.atomics.DistributedLong;
import io.atomix.atomics.internal.LongCommands;
import io.atomix.atomics.internal.LongService;

import java.util.Properties;

/**
 * Distributed long factory.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class DistributedLongFactory implements ResourceFactory<DistributedLong> {

  @Override
  public SerializableTypeResolver createSerializableTypeResolver() {
    return new LongCommands.TypeResolver();
  }

  @Override
  public ResourceStateMachine createStateMachine(Properties config) {
    return new LongService(config);
  }

  @Override
  public DistributedLong createInstance(CopycatClient client, Properties options) {
    return new DistributedLong(client, options);
  }

}
