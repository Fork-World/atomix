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
package net.kuujo.copycat.io;

import net.kuujo.copycat.io.util.ReferencePool;

/**
 * Buffer writer pool.
 * <p>
 * The writer pool reduces garbage produced by frequent reads by tracking references to existing writers and recycling
 * writers once they're closed.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class BufferWriterPool extends ReferencePool<BufferWriter> {
  private final Buffer buffer;

  public BufferWriterPool(Buffer buffer) {
    super(r -> new BufferWriter(buffer.bytes(), 0, 0, r));
    this.buffer = buffer;
  }

  @Override
  public BufferWriter acquire() {
    BufferWriter writer = super.acquire();
    buffer.acquire();
    return writer;
  }

  @Override
  public void release(BufferWriter reference) {
    buffer.release();
    super.release(reference);
  }

}
