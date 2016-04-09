/*
 * Copyright (C) 2016 Kane O'Riley
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.oriley.twiddle;

import android.os.Handler;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings(value = {"unused", "WeakerAccess"})
public abstract class TwiddleLoader<T, A, P> {

    private static final String TAG = TwiddleLoader.class.getSimpleName();
    private static final int NO_DELAY = -1;

    // From AsyncTask
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;

    @NonNull
    private final Map<T, A> mReferenceMap = Collections.synchronizedMap(new WeakHashMap<T, A>());

    @NonNull
    private final ScheduledThreadPoolExecutor mExecutorService;

    @NonNull
    private final Handler mHandler = new Handler();

    private final long mDelayMillis;


    public TwiddleLoader() {
        this(0);
    }

    public TwiddleLoader(long loadDelayMillis) {
        mDelayMillis = loadDelayMillis;

        mExecutorService = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);
        mExecutorService.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        mExecutorService.setKeepAliveTime(0L, TimeUnit.MILLISECONDS);
    }

    public TwiddleLoader(long loadDelayMillis, int corePoolSize, int maxPoolSize) {
        mDelayMillis = loadDelayMillis;

        mExecutorService = new ScheduledThreadPoolExecutor(CORE_POOL_SIZE);
        mExecutorService.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        mExecutorService.setKeepAliveTime(0L, TimeUnit.MILLISECONDS);
    }


    public void loadInto(@NonNull T target, @NonNull A asset) {
        loadInto(target, asset, NO_DELAY);
    }

    public void loadInto(@NonNull T target, @NonNull A asset, long delay) {
        if (initialiseTarget(target, asset)) {
            mReferenceMap.put(target, asset);
            queueAsset(target, asset, delay);
        }
    }

    protected void scheduleRunnable(@NonNull Runnable runnable, long delayMillis) {
        mExecutorService.schedule(runnable, delayMillis >= 0 ? delayMillis : mDelayMillis, TimeUnit.MILLISECONDS);
    }

    // Return true if loading needs to continue
    protected abstract boolean initialiseTarget(@NonNull T target, @NonNull A asset);

    @NonNull
    protected abstract Result<P> load(@NonNull T target, @NonNull A asset);

    protected abstract void apply(@NonNull T target, @NonNull Result<P> result);

    private void queueAsset(@NonNull T target, @NonNull A asset, long delay) {
        PendingTarget p = new PendingTarget(target, asset);
        scheduleRunnable(new PayloadRunnable(p), delay >= 0 ? delay : mDelayMillis);
    }

    @CallSuper
    public void dispose() {
        mHandler.removeCallbacksAndMessages(null);
        mExecutorService.shutdown();
    }

    protected boolean isReused(@NonNull PendingTarget pendingTarget) {
        return isReused(pendingTarget.target, pendingTarget.asset);
    }

    protected boolean isReused(@NonNull T target, @NonNull A asset) {
        return mReferenceMap.get(target) != asset;
    }

    private final class PendingTarget {

        @NonNull
        private final T target;

        @NonNull
        private final A asset;

        PendingTarget(@NonNull T t, @NonNull A a) {
            target = t;
            asset = a;
        }
    }

    private final class PayloadRunnable implements Runnable {

        @NonNull
        final PendingTarget pendingTarget;

        PayloadRunnable(@NonNull PendingTarget p) {
            pendingTarget = p;
        }

        @Override
        public void run() {
            if (isReused(pendingTarget)) {
                return;
            }

            try {
                Result<P> p = load(pendingTarget.target, pendingTarget.asset);
                if (!isReused(pendingTarget)) {
                    mHandler.post(new UpdateUiRunnable(pendingTarget, p));
                }
            } catch (Throwable t) {
                Log.e(TAG, "error loading payload for asset " + pendingTarget.asset, t);
            }
        }
    }

    private final class UpdateUiRunnable implements Runnable {

        @NonNull
        final PendingTarget pendingTarget;

        @Nullable
        final Result<P> result;

        UpdateUiRunnable(@NonNull PendingTarget t, @Nullable Result<P> p) {
            pendingTarget = t;
            result = p;
        }

        @Override
        public void run() {
            if (!isReused(pendingTarget) && result != null) {
                apply(pendingTarget.target, result);
            }
        }
    }

    public final class Result<R> {

        @Nullable
        public final R payload;

        @NonNull
        public final A asset;

        public final boolean cached;

        public Result(@Nullable R payload, @NonNull A asset, boolean cached) {
            this.payload = payload;
            this.asset = asset;
            this.cached = cached;
        }
    }
}