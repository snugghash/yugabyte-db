// Copyright (c) YugaByte, Inc.

package com.yugabyte.sample.common;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;


public class SimpleLoadGenerator {
  private static final Logger LOG = Logger.getLogger(SimpleLoadGenerator.class);

  public static class Key {
    // The underlying key is an integer.
    Long key;
    // The randomized loadtester prefix.
    String keyPrefix = CmdLineOpts.loadTesterUUID.toString();

    public Key(long key, String keyPrefix) {
      this.key = new Long(key);
      if (keyPrefix != null) {
        this.keyPrefix = keyPrefix;
      }
    }

    public long asNumber() {
      return key;
    }

    public String asString() {
      return keyPrefix + key.toString();
    }

    public String getValueStr() {
      return ("val:" + key.toString());
    }

    public void verify(String value) {
      if (!value.equals(getValueStr())) {
        LOG.fatal("Value mismatch for key: " + key.toString() +
                  ", expected: " + getValueStr() +
                  ", got: " + value);
      }
    }

    @Override
    public String toString() {
      return "Key: " + key + ", value: " + getValueStr();
    }
  }

  // The key to start from.
  final long startKey;
  // The key to write till.
  final long endKey;
  // The max key that was successfully written consecutively.
  AtomicLong maxWrittenKey;
  // The max key that has been generated and handed out so far.
  AtomicLong maxGeneratedKey;
  // Set of keys that failed to write.
  Set<Long> failedKeys;
  // Keys that have been written above maxWrittenKey.
  Set<Long> writtenKeys;
  // A background thread to track keys written and increment maxWrittenKey.
  Thread writtenKeysTracker;
  // The prefix for the key.
  String keyPrefix;
  // Random number generator.
  Random random = new Random();

  public SimpleLoadGenerator(long startKey, long endKey) {
    this.startKey = startKey;
    this.endKey = endKey;
    maxWrittenKey = new AtomicLong(-1);
    maxGeneratedKey = new AtomicLong(-1);
    failedKeys = new HashSet<Long>();
    writtenKeys = new HashSet<Long>();
    writtenKeysTracker = new Thread("Written Keys Tracker") {
        @Override
        public void run() {
          do {
            long key = maxWrittenKey.get() + 1;
            synchronized (this) {
              if (failedKeys.contains(key) || writtenKeys.remove(key)) {
                maxWrittenKey.set(key);
              } else {
                try {
                  wait();
                } catch (InterruptedException e) {
                  // Ignore
                }
              };
            }
          } while (true);
        }
      };
    // Make tracker a daemon thread so that it will not block the load tester from exiting
    // when done.
    writtenKeysTracker.setDaemon(true);
    writtenKeysTracker.start();
  }

  public void setKeyPrefix(String prefix) {
    keyPrefix = prefix;
  }

  public void recordWriteSuccess(Key key) {
    synchronized (writtenKeysTracker) {
      writtenKeys.add(key.asNumber());
      writtenKeysTracker.notify();
    }
  }

  public void recordWriteFailure(Key key) {
    synchronized (writtenKeysTracker) {
      failedKeys.add(key.asNumber());
      writtenKeysTracker.notify();
    }
  }

  public Key getKeyToWrite() {
    // Return a random key to update if we have already written all keys.
    if (maxGeneratedKey.get() != -1 && maxGeneratedKey.get() == endKey - 1) {
      return getKeyToRead();
    }
    return generateKey(maxGeneratedKey.incrementAndGet());
  }

  public Key getKeyToRead() {
    long maxKey = maxWrittenKey.get();
    if (maxKey < 0) {
      return null;
    } else if (maxKey == 0) {
      return generateKey(0);
    }
    do {
      long key = ThreadLocalRandom.current().nextLong(maxKey);
      if (!failedKeys.contains(key))
        return generateKey(key);
    } while (true);
  }

  private Key generateKey(long key) {
    return new Key(key, keyPrefix);
  }
}