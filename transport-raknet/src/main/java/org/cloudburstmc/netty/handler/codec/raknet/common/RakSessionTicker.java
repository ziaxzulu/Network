/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.handler.codec.raknet.common;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces session maintenance onto one allocation-stable task per event loop and interval. */
final class RakSessionTicker {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakSessionTicker.class);
    private static final ConcurrentMap<Key, Coordinator> COORDINATORS = new ConcurrentHashMap<>();

    private RakSessionTicker() {
    }

    static Registration register(EventLoop eventLoop, int intervalMillis, Runnable task) {
        Objects.requireNonNull(eventLoop, "eventLoop");
        Objects.requireNonNull(task, "task");
        Key key = new Key(eventLoop, intervalMillis);
        while (true) {
            Coordinator coordinator = COORDINATORS.computeIfAbsent(key, Coordinator::new);
            Registration registration = coordinator.register(task);
            if (registration != null) {
                return registration;
            }
            COORDINATORS.remove(key, coordinator);
        }
    }

    static int coordinatorCount(EventLoop eventLoop, int intervalMillis) {
        return COORDINATORS.containsKey(new Key(eventLoop, intervalMillis)) ? 1 : 0;
    }

    interface Registration {
        void cancel();
    }

    private static final class Coordinator implements Runnable {
        private final Key key;
        private final CopyOnWriteArrayList<RegistrationImpl> registrations = new CopyOnWriteArrayList<>();
        private ScheduledFuture<?> future;
        private boolean retired;
        private int firstRegistration;

        private Coordinator(Key key) {
            this.key = key;
            key.eventLoop.terminationFuture().addListener(ignored -> this.retire());
        }

        private synchronized Registration register(Runnable task) {
            if (this.retired) {
                return null;
            }
            RegistrationImpl registration = new RegistrationImpl(this, task);
            this.registrations.add(registration);
            if (this.future == null) {
                try {
                    this.future = this.key.eventLoop.scheduleAtFixedRate(
                            this, 0, this.key.intervalMillis, TimeUnit.MILLISECONDS);
                } catch (RuntimeException | Error throwable) {
                    this.registrations.remove(registration);
                    this.retired = true;
                    COORDINATORS.remove(this.key, this);
                    throw throwable;
                }
            }
            return registration;
        }

        private synchronized void unregister(RegistrationImpl registration) {
            this.registrations.remove(registration);
            if (this.registrations.isEmpty()) {
                this.retire();
            }
        }

        private synchronized void retire() {
            if (this.retired) {
                return;
            }
            this.retired = true;
            if (this.future != null) {
                this.future.cancel(false);
                this.future = null;
            }
            this.registrations.clear();
            COORDINATORS.remove(this.key, this);
        }

        @Override
        public void run() {
            RegistrationImpl[] snapshot = this.registrations.toArray(new RegistrationImpl[0]);
            int length = snapshot.length;
            int start = length == 0 ? 0 : Math.floorMod(this.firstRegistration++, length);
            for (int i = 0; i < length; i++) {
                RegistrationImpl registration = snapshot[(start + i) % length];
                if (registration.cancelled.get()) {
                    continue;
                }
                try {
                    registration.task.run();
                } catch (Throwable throwable) {
                    log.error("Unexpected failure in shared RakNet session tick", throwable);
                }
            }

        }
    }

    private static final class RegistrationImpl implements Registration {
        private final Coordinator coordinator;
        private final Runnable task;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RegistrationImpl(Coordinator coordinator, Runnable task) {
            this.coordinator = coordinator;
            this.task = task;
        }

        @Override
        public void cancel() {
            if (this.cancelled.compareAndSet(false, true)) {
                this.coordinator.unregister(this);
            }
        }
    }

    private static final class Key {
        private final EventLoop eventLoop;
        private final int intervalMillis;

        private Key(EventLoop eventLoop, int intervalMillis) {
            if (intervalMillis <= 0) {
                throw new IllegalArgumentException("intervalMillis must be positive");
            }
            this.eventLoop = eventLoop;
            this.intervalMillis = intervalMillis;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Key)) {
                return false;
            }
            Key other = (Key) object;
            return this.eventLoop == other.eventLoop && this.intervalMillis == other.intervalMillis;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(this.eventLoop) + this.intervalMillis;
        }
    }
}
