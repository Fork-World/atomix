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
package net.kuujo.copycat.raft.log.entry;

import net.kuujo.alleycat.SerializeWith;
import net.kuujo.alleycat.util.ReferenceManager;

/**
 * Register client entry.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=303)
public class RegisterEntry extends TimestampedEntry<RegisterEntry> {

  public RegisterEntry() {
  }

  public RegisterEntry(ReferenceManager<Entry<?>> referenceManager) {
    super(referenceManager);
  }

}