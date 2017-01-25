/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.stram.stream;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.apex.engine.serde.BufferSlice;
import org.apache.apex.engine.serde.PartitionSerde;
import org.apache.apex.engine.serde.SerializationBuffer;
import org.apache.apex.engine.serde.WindowedBlockStream;

import com.datatorrent.api.StreamCodec;
import com.datatorrent.bufferserver.client.Publisher;
import com.datatorrent.bufferserver.packet.BeginWindowTuple;
import com.datatorrent.bufferserver.packet.DataTuple;
import com.datatorrent.bufferserver.packet.EndStreamTuple;
import com.datatorrent.bufferserver.packet.EndWindowTuple;
import com.datatorrent.bufferserver.packet.MessageType;
import com.datatorrent.bufferserver.packet.PayloadTuple;
import com.datatorrent.bufferserver.packet.ResetWindowTuple;
import com.datatorrent.bufferserver.packet.WindowIdTuple;
import com.datatorrent.bufferserver.util.Codec;
import com.datatorrent.netlet.EventLoop;
import com.datatorrent.netlet.NetletThrowable;
import com.datatorrent.netlet.util.CircularBuffer;
import com.datatorrent.netlet.util.Slice;
import com.datatorrent.netlet.util.VarInt;
import com.datatorrent.stram.codec.StatefulStreamCodec;
import com.datatorrent.stram.codec.StatefulStreamCodec.DataStatePair;
import com.datatorrent.stram.engine.ByteCounterStream;
import com.datatorrent.stram.engine.StreamContext;
import com.datatorrent.stram.tuple.Tuple;

import static java.lang.Thread.sleep;

/**
 * Implements tuple flow of node to then buffer server in a logical stream<p>
 * <br>
 * Extends SocketOutputStream as buffer server and node communicate via a socket<br>
 * This buffer server is a write instance of a stream and hence would take care of persistence and retaining tuples till they are consumed<br>
 * Partitioning is managed by this instance of the buffer server<br>
 * <br>
 *
 * @since 0.3.2
 */
public class BufferServerPublisher extends Publisher implements ByteCounterStream
{
  private StreamCodec<Object> serde;
  private final AtomicLong publishedByteCount;
  private EventLoop eventloop;
  private int count;
  private StatefulStreamCodec<Object> statefulSerde;

  public BufferServerPublisher(String sourceId, int queueCapacity)
  {
    super(sourceId, queueCapacity);
    this.publishedByteCount = new AtomicLong(0);
  }

  /**
   *
   * @param payload
   */
  private int implementor = 3;    //0: old implementation; 1: implementation 1 thread; 2: implementation 2 threads; 3: as batch
  @Override
  public void put(Object payload)
  {
    switch(implementor) {
      case 0:
        put_old(payload);
        break;

      case 1:
        put_new(payload);
        break;

      case 2:
      case 3:
        this.put_new_2threads(payload);
        break;

      default:
        throw new RuntimeException("Invalid.");
    }
  }

  @SuppressWarnings("SleepWhileInLoop")
  public void put_old(Object payload)
  {
    count++;
    byte[] array;
    if (payload instanceof Tuple) {
      final Tuple t = (Tuple)payload;

      switch (t.getType()) {
        case CHECKPOINT:
          if (statefulSerde != null) {
            statefulSerde.resetState();
          }
          array = WindowIdTuple.getSerializedTuple((int)t.getWindowId());
          array[0] = MessageType.CHECKPOINT_VALUE;
          break;

        case BEGIN_WINDOW:
          array = BeginWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_WINDOW:
          array = EndWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_STREAM:
          array = EndStreamTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case RESET_WINDOW:
          com.datatorrent.stram.tuple.ResetWindowTuple rwt = (com.datatorrent.stram.tuple.ResetWindowTuple)t;
          array = ResetWindowTuple.getSerializedTuple(rwt.getBaseSeconds(), rwt.getIntervalMillis());
          break;

        default:
          throw new UnsupportedOperationException("this data type is not handled in the stream");
      }
    } else {
      if (statefulSerde == null) {
        array = PayloadTuple.getSerializedTuple(serde.getPartition(payload), serde.toByteArray(payload));
      } else {
        DataStatePair dsp = statefulSerde.toDataStatePair(payload);
        /*
         * if there is any state write that for the subscriber before we write the data.
         */
        if (dsp.state != null) {
          array = DataTuple.getSerializedTuple(MessageType.CODEC_STATE_VALUE, dsp.state);
          try {
            while (!write(array)) {
              sleep(5);
            }
          } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
          }
        }
        /*
         * Now that the state if any has been sent, we can proceed with the actual data we want to send.
         */
        array = PayloadTuple.getSerializedTuple(statefulSerde.getPartition(payload), dsp.data);
      }
    }

    try {
      while (!write(array)) {
        sleep(5);
      }
      publishedByteCount.addAndGet(array.length);
    } catch (InterruptedException ie) {
      throw new RuntimeException(ie);
    }
  }

  /**
   * serialize only, a dedicated thread response for write.
   * As serialize is cpu concentrate and write should be I/O concentrate. two thread should increase the performance
   * @param payload
   */
  private PartitionSerde partitionSerde = new PartitionSerde();
  private SerializationBuffer serializationBuffer = SerializationBuffer.READ_BUFFER;
  private int tupleCount = 0;
  @SuppressWarnings({ "SleepWhileInLoop", "unchecked" })
  public void put_new(Object payload)
  {
    count++;
    byte[] array;
    Slice slice = null;
    if (payload instanceof Tuple) {
      final Tuple t = (Tuple)payload;

      switch (t.getType()) {
        case CHECKPOINT:
          if (statefulSerde != null) {
            statefulSerde.resetState();
          }
          array = WindowIdTuple.getSerializedTuple((int)t.getWindowId());
          array[0] = MessageType.CHECKPOINT_VALUE;
          break;

        case BEGIN_WINDOW:
          array = BeginWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_WINDOW:
          array = EndWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_STREAM:
          array = EndStreamTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case RESET_WINDOW:
          com.datatorrent.stram.tuple.ResetWindowTuple rwt = (com.datatorrent.stram.tuple.ResetWindowTuple)t;
          array = ResetWindowTuple.getSerializedTuple(rwt.getBaseSeconds(), rwt.getIntervalMillis());
          break;

        default:
          throw new UnsupportedOperationException("this data type is not handled in the stream");
      }


      try {
        while (!write(array)) {
          sleep(5);
        }
        publishedByteCount.addAndGet(array.length);
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }

    } else {
      slice = partitionSerde.serialize(payload.hashCode(), payload, serializationBuffer);
      try {
        if (slice != null) {
          while (!write(slice.buffer, slice.offset, slice.length)) {
            sleep(5);
          }
          publishedByteCount.addAndGet(slice.length);
        }
      } catch (InterruptedException ie) {
        throw new RuntimeException(ie);
      }
      if(++tupleCount == 1000) {
        serializationBuffer.reset();
        tupleCount = 0;
      }
    }
  }

  private final int SLICE_NUM = 1;
  private SerializationBuffer inputBuffer = new SerializationBuffer(new WindowedBlockStream());
  private Slice[] inputSlices = new Slice[SLICE_NUM];
  private AtomicInteger inputSlicesIndex = new AtomicInteger(-1);
  private SerializationBuffer outputBuffer = new SerializationBuffer(new WindowedBlockStream());
  private Slice[] outputSlices = new Slice[SLICE_NUM];
  private int outputSlicesIndex = -1;
  //set this flag when writer buffer emptied.
  private AtomicBoolean switchImmediately = new AtomicBoolean(false);
  //set this flag when serialize buffer is full.
  //private AtomicBoolean requestSwitch = new AtomicBoolean();
  //private Object serializationReady = new Object();
  private Object writerReady = new Object();
  private Object switching = new Object();

  public long putCount = 0;
  /**
   * same as put_new(), but has seperate threads for serialization and write
   * @param payload
   */
  @SuppressWarnings({ "SleepWhileInLoop", "unchecked" })
  public  void put_new_2threads(Object payload)
  {
    //in case use this buffer by mistake
    this.serializationBuffer = null;

    synchronized(switching) {
    count++;
    byte[] array;
    if (payload instanceof Tuple) {
      final Tuple t = (Tuple)payload;

      switch (t.getType()) {
        case CHECKPOINT:
          if (statefulSerde != null) {
            statefulSerde.resetState();
          }
          array = WindowIdTuple.getSerializedTuple((int)t.getWindowId());
          array[0] = MessageType.CHECKPOINT_VALUE;
          break;

        case BEGIN_WINDOW:
          array = BeginWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_WINDOW:
          array = EndWindowTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case END_STREAM:
          array = EndStreamTuple.getSerializedTuple((int)t.getWindowId());
          break;

        case RESET_WINDOW:
          com.datatorrent.stram.tuple.ResetWindowTuple rwt = (com.datatorrent.stram.tuple.ResetWindowTuple)t;
          array = ResetWindowTuple.getSerializedTuple(rwt.getBaseSeconds(), rwt.getIntervalMillis());
          break;

        default:
          throw new UnsupportedOperationException("this data type is not handled in the stream");
      }

      inputBuffer.write(array);
      Slice slice = inputBuffer.toSlice();
      addInputSlice(slice);

    } else {
      addInputSlice(partitionSerde.serialize(payload.hashCode(), payload, inputBuffer));
    }
      putCount++;

    }
  }

  private void addInputSlice(Slice slice)
  {
    int index = inputSlicesIndex.get() + 1;
    if (index >= SLICE_NUM) {
      if (implementor == 2) {
        try {
          switching.wait();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        index = inputSlicesIndex.get() + 1;
      } else {
        //run as batch
        writer.runOnce();
      }
      index = 0;
    }

    inputSlices[index] = slice;
    inputSlicesIndex.set(index);
  }

  private void switchInputOutput()
  {
    synchronized (switching) {
      //outputBuffer.reset();

      SerializationBuffer tmpBuffer = inputBuffer;
      Slice[] tmpSlices = inputSlices;

      inputBuffer = outputBuffer;
      inputSlices = outputSlices;

      outputBuffer = tmpBuffer;
      outputSlices = tmpSlices;
      outputSlicesIndex = inputSlicesIndex.get();

      inputSlicesIndex.set(-1);
      switchImmediately.set(false);
      //requestSwitch.set(false);

      switching.notifyAll();
    }
  }

  public long writtenCount = 0;
//
//  private ArrayList<Slice> outputSlices = new ArrayList<>(SLICE_NUM);
//  private ArrayList<Slice> inputSlices = new ArrayList<>(SLICE_NUM);

  private static class Writer implements Runnable
  {
    BufferServerPublisher publisher;


    private Writer(BufferServerPublisher publisher)
    {
      this.publisher = publisher;
    }

    @Override
    public void run()
    {
      while (true) {
        runOnce();
      }
    }

    public void runOnce()
    {
      if (publisher.inputSlicesIndex.get() >= 0) {
        //publisher.switchImmediately.set(true);
        publisher.switchInputOutput();
      }else{
        publisher.sleepSlient(1);
        return;
      }

      int writeSliceCount = publisher.outputSlicesIndex + 1;
      for (int i = 0; i < writeSliceCount; ++i) {
        Slice slice = publisher.outputSlices[i];
        if (slice != null) {
          if(slice.offset < 0 || slice.length <= 0) {
            throw new RuntimeException("Invalid slice");
          }
          while (!publisher.write(slice.buffer, slice.offset, slice.length)) {
            System.out.println("!!!!!!!!!!!write failed!!!!!!!!!!!!!!!!");
            publisher.sleepSlient(5);
          }
          publisher.publishedByteCount.addAndGet(slice.length);
        }
      }
      publisher.outputBuffer.reset();
      publisher.writtenCount += writeSliceCount;

//      System.out.println("outputBuffer capacity: " + publisher.outputBuffer.capacity() + "; inputBuffer capacity: " + publisher.inputBuffer.capacity()
//      + "; current writeSliceCount: " + writeSliceCount + "; total writtenCount: " + publisher.writtenCount);
    }
  }


  BufferSlice expectedSlice = new BufferSlice(new byte[]{1, 53, 16, -78, -112, 3, 1, 49, 50, 51, 52, 53, 54, 55, 56, -71});  //{1, 49, 0, 0, 0, 3, 1, -126, 49});

  private int messageCount = 0;
  private int intOffset;
  private static final int INT_ARRAY_SIZE = 4096 - 5;
  private byte[] intBuffer = new byte[INT_ARRAY_SIZE + 5];
  @Override
  public boolean write(byte[] message, int offset, int size)
  {
    if (messageCount++ > 30) {
      sleepSlient(1);
      return true;
    }

    Slice slice = new Slice(message, offset, size);
    System.out.println("send: " + messageCount + ": " + slice);

    if(size <= 0) {
      throw new RuntimeException("Invalid size: " + size);
    }
    if (sendBuffer4Offers.remainingCapacity() < 2 && sendBuffer4Offers.capacity() == MAX_SENDBUFFER_SIZE) {
      return false;
    }

    if (intOffset > INT_ARRAY_SIZE) {
      intBuffer = new byte[INT_ARRAY_SIZE + 5];
      intOffset = 0;
    }

    int newOffset = VarInt.write(size, intBuffer, intOffset);
    if(newOffset - intOffset <= 0) {
      throw new RuntimeException("Invalid size: " + (newOffset - intOffset));
    }
    if (send(intBuffer, intOffset, newOffset - intOffset)) {
      intOffset = newOffset;
      if (send(message, offset, size)) {
        //this.sleepSlient(1);
        return true;
      }

      logger.debug("Exiting sendBuffer for Offers = {}, socket = {}", sendBuffer4Offers, key.channel());
      System.exit(0);
      throw new IllegalStateException("Only partial data could be written!");
    }

    return false;
  }

  @Override
  public boolean send(byte[] array, int offset, int len)
  {
    if (!throwables.isEmpty()) {
      NetletThrowable.Util.throwRuntime(throwables.pollUnsafe());
    }

    Slice f;
    if (freeBuffer.isEmpty()) {
      f = new Slice(array, offset, len);
    }
    else {
      f = freeBuffer.pollUnsafe();
      f.buffer = array;
      f.offset = offset;
      f.length = len;
    }

    if (sendBuffer4Offers.offer(f)) {
      synchronized (bufferOfBuffers) {
        if (!write) {
          key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
          write = true;
          key.selector().wakeup();
        }
      }

      return true;
    }

    if (!throwables.isEmpty()) {
      NetletThrowable.Util.throwRuntime(throwables.pollUnsafe());
    }

    if (sendBuffer4Offers.capacity() != MAX_SENDBUFFER_SIZE) {
      synchronized (bufferOfBuffers) {
        if (sendBuffer4Offers != sendBuffer4Polls) {
          bufferOfBuffers.add(sendBuffer4Offers);
        }

        sendBuffer4Offers = new CircularBuffer<Slice>(sendBuffer4Offers.capacity() << 1);
        sendBuffer4Offers.add(f);
        if (!write) {
          key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
          write = true;
          key.selector().wakeup();
        }

        return true;
      }
    }

    return false;
  }


  private void sleepSlient(int millis)
  {
    try {
      sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  /**
   *
   * @param context
   */
  @Override
  @SuppressWarnings("unchecked")
  public void activate(StreamContext context)
  {
    setToken(context.get(StreamContext.BUFFER_SERVER_TOKEN));
    InetSocketAddress address = context.getBufferServerAddress();
    eventloop = context.get(StreamContext.EVENT_LOOP);
    eventloop.connect(address.isUnresolved() ? new InetSocketAddress(address.getHostName(), address.getPort()) : address, this);

    logger.debug("Registering publisher: {} {} windowId={} server={}", new Object[] {context.getSourceId(), context.getId(), Codec.getStringWindowId(context.getFinishedWindowId()), context.getBufferServerAddress()});
    super.activate(null, context.getFinishedWindowId());
  }

  @Override
  public void deactivate()
  {
    setToken(null);
    eventloop.disconnect(this);
  }

  @Override
  public void onMessage(byte[] buffer, int offset, int size)
  {
    throw new RuntimeException("OutputStream is not supposed to receive anything!");
  }

  private Writer writer = null;
  @Override
  @SuppressWarnings("unchecked")
  public void setup(StreamContext context)
  {
    StreamCodec<?> codec = context.get(StreamContext.CODEC);
    if (codec == null) {
      statefulSerde = ((StatefulStreamCodec<Object>)StreamContext.CODEC.defaultValue).newInstance();
    } else if (codec instanceof StatefulStreamCodec) {
      statefulSerde = ((StatefulStreamCodec<Object>)codec).newInstance();
    } else {
      serde = (StreamCodec<Object>)codec;
    }
    if(implementor == 2) {
      new Thread(new Writer(this)).start();
    } else if(implementor == 3) {
      writer = new Writer(this);
    }
  }

  @Override
  public void teardown()
  {
  }

  @Override
  public long getByteCount(boolean reset)
  {
    if (reset) {
      return publishedByteCount.getAndSet(0);
    }

    return publishedByteCount.get();
  }

  @Override
  public int getCount(boolean reset)
  {
    try {
      return count;
    } finally {
      if (reset) {
        count = 0;
      }
    }
  }

  private static final Logger logger = LoggerFactory.getLogger(BufferServerPublisher.class);
}
