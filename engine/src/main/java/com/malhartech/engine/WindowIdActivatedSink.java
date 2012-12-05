/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.engine;

import com.malhartech.api.Sink;
import com.malhartech.bufferserver.Buffer;

/**
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class WindowIdActivatedSink<T> implements Sink<T>
{
  private final Sink<Object> sink;
  private final long windowId;
  private final Stream<Object> stream;
  private final String identifier;

  public WindowIdActivatedSink(Stream<Object> stream, String identifier, final Sink<Object> sink, final long windowId)
  {
    this.stream = stream;
    this.identifier = identifier;
    this.sink = sink;
    this.windowId = windowId;
  }

  @Override
  public void process(Object payload)
  {
    if (payload instanceof Tuple) {
      switch (((Tuple)payload).getType()) {
        case BEGIN_WINDOW:
          if (((Tuple)payload).getWindowId() > windowId) {
            sink.process(payload);
            stream.setSink(identifier, sink);
          }
          break;

        case CODEC_STATE:
          sink.process(payload);
          break;
      }
    }
  }
}
