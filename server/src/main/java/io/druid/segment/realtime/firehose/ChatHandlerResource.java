/*
 * Druid - a distributed column store.
 * Copyright 2012 - 2015 Metamarkets Group Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.druid.segment.realtime.firehose;

import com.google.common.base.Optional;
import com.google.inject.Inject;

import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

@Path("/druid/worker/v1")
public class ChatHandlerResource
{
  private final ChatHandlerProvider handlers;

  @Inject
  public ChatHandlerResource(ChatHandlerProvider handlers)
  {
    this.handlers = handlers;
  }

  @Path("/chat/{id}")
  public Object doTaskChat(
      @PathParam("id") String handlerId
  )
  {
    final Optional<ChatHandler> handler = handlers.get(handlerId);

    if (handler.isPresent()) {
      return handler.get();
    } else {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
  }
}
