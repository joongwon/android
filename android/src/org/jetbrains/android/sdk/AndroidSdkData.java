/*
 * Copyright 2000-2010 JetBrains s.r.o.
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

package org.jetbrains.android.sdk;

import com.android.SdkConstants;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.Log;
import com.android.sdklib.BuildToolInfo;
import com.android.sdklib.IAndroidTarget;
import com.android.sdklib.devices.DeviceManager;
import com.android.sdklib.repository.local.LocalSdk;
import com.android.tools.idea.sdk.DefaultSdks;
import com.google.common.collect.Lists;
import com.intellij.CommonBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.reference.SoftReference;
import com.intellij.util.containers.HashMap;
import org.jetbrains.android.actions.AndroidEnableAdbServiceAction;
import org.jetbrains.android.logcat.AdbErrors;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidSdkData {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.sdk.AndroidSdkData");

  private static volatile boolean myDdmLibInitialized = false;
  private static volatile boolean myAdbCrashed = false;
  private static final Object myDdmsLock = new Object();

  private final Map<IAndroidTarget, SoftReference<AndroidTargetData>> myTargetDatas =
    new HashMap<IAndroidTarget, SoftReference<AndroidTargetData>>();

  private final LocalSdk myLocalSdk;
  private final DeviceManager myDeviceManager;

  private final int myPlatformToolsRevision;
  private final int mySdkToolsRevision;

  private static final List<SoftReference<AndroidSdkData>> mInstances = Lists.newArrayList();

  /** Singleton access classes */

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull File sdkLocation) {
    return getSdkData(sdkLocation, false);
  }

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull File sdkLocation, boolean forceReparse) {
    File canonicalLocation = new File(FileUtil.toCanonicalPath(sdkLocation.getPath()));

    if (!forceReparse) {
      Iterator<SoftReference<AndroidSdkData>> it = mInstances.iterator();
      while (it.hasNext()) {
        AndroidSdkData sdkData = it.next().get();
        // Lazily remove stale soft references
        if (sdkData == null) {
          it.remove();
          continue;
        }
        if (FileUtil.filesEqual(sdkData.getLocation(), canonicalLocation)) {
          return sdkData;
        }
      }
    }
    if (!DefaultSdks.validateAndroidSdkPath(canonicalLocation)) {
      return null;
    }
    LocalSdk localSdk = new LocalSdk(canonicalLocation);
    AndroidSdkData sdkData = new AndroidSdkData((localSdk));
    mInstances.add(0, new SoftReference<AndroidSdkData>(sdkData));
    return mInstances.get(0).get();
  }

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull String sdkPath) {
    File file = new File(FileUtil.toSystemDependentName(sdkPath));
    return getSdkData(file);
  }

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull Sdk sdk) {
    String sdkHomePath = sdk.getHomePath();
    if (sdkHomePath != null) {
      return getSdkData(sdk.getHomePath());
    }
    return null;
  }

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull Project project) {
    Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
    if (sdk != null) {
      return getSdkData(sdk);
    }
    return null;
  }

  @Nullable
  public static AndroidSdkData getSdkData(@NotNull Module module) {
    return getSdkData(module.getProject());
  }

  private AndroidSdkData(@NotNull LocalSdk localSdk) {
    myLocalSdk = localSdk;
    myPlatformToolsRevision = AndroidCommonUtils.parsePackageRevision(localSdk.getPath(), SdkConstants.FD_PLATFORM_TOOLS);
    mySdkToolsRevision = AndroidCommonUtils.parsePackageRevision(localSdk.getPath(), SdkConstants.FD_TOOLS);
    myDeviceManager = DeviceManager.createInstance(localSdk.getLocation(), new MessageBuildingSdkLog());
  }

  @NotNull
  public File getLocation() {
    File location = myLocalSdk.getLocation();

    // The LocalSdk should always have been initialized.
    assert location != null;

    return location;
  }

  @Deprecated
  @NotNull
  public String getPath() {
    return getLocation().getPath();
  }

  @Nullable
  public BuildToolInfo getLatestBuildTool() {
    return myLocalSdk.getLatestBuildTool();
  }

  @NotNull
  public IAndroidTarget[] getTargets() {
    return myLocalSdk.getTargets();
  }

  // be careful! target name is NOT unique

  @Nullable
  public IAndroidTarget findTargetByName(@NotNull String name) {
    for (IAndroidTarget target : getTargets()) {
      if (target.getName().equals(name)) {
        return target;
      }
    }
    return null;
  }

  @Nullable
  public IAndroidTarget findTargetByApiLevel(@NotNull String apiLevel) {
    IAndroidTarget candidate = null;
    for (IAndroidTarget target : getTargets()) {
      if (AndroidSdkUtils.targetHasId(target, apiLevel)) {
        if (target.isPlatform()) {
          return target;
        }
        else if (candidate == null) {
          candidate = target;
        }
      }
    }
    return candidate;
  }

  @Nullable
  public IAndroidTarget findTargetByHashString(@NotNull String hashString) {
    return myLocalSdk.getTargetFromHashString(hashString);
  }

  public int getPlatformToolsRevision() {
    return myPlatformToolsRevision;
  }

  public int getSdkToolsRevision() {
    return mySdkToolsRevision;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) return false;
    if (obj.getClass() != getClass()) return false;
    AndroidSdkData sdkData = (AndroidSdkData)obj;
    return FileUtil.filesEqual(getLocation(), sdkData.getLocation());
  }

  @Override
  public int hashCode() {
    return getLocation().hashCode();
  }

  @SuppressWarnings("AssignmentToStaticFieldFromInstanceMethod")
  private boolean initializeDdmlib(@NotNull Project project) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    while (true) {
      final MyInitializeDdmlibTask task = new MyInitializeDdmlibTask(project);

      AdbErrors.clear();

      Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          doInitializeDdmlib();
          task.finish();
        }
      });

      t.start();

      boolean retryWas = false;

      while (!task.isFinished()) {
        ProgressManager.getInstance().run(task);

        boolean finished = task.isFinished();

        if (task.isCanceled()) {
          myAdbCrashed = !finished;
          forceInterrupt(t);
          return false;
        }

        myAdbCrashed = false;

        if (!finished) {
          final String adbErrorString = combine(AdbErrors.getErrors());
          final int result = Messages.showDialog(project, "ADB not responding. You can wait more, or kill \"" +
                                                          SdkConstants.FN_ADB +
                                                          "\" process manually and click 'Restart'" +
                                                          (adbErrorString.length() > 0 ? "\nErrors from ADB:\n" + adbErrorString : ""),
                                                 CommonBundle.getErrorTitle(), new String[]{"&Wait more", "&Restart", "&Cancel"}, 0,
                                                 Messages.getErrorIcon());
          if (result == 2) {
            // cancel
            myAdbCrashed = true;
            forceInterrupt(t);
            return false;
          }
          else if (result == 1) {
            // restart
            myAdbCrashed = true;
            retryWas = true;
          }
        }
      }

      // task finished, but if we had problems, ddmlib can be still initialized incorrectly, so we invoke initialize once again
      if (!retryWas) {
        break;
      }
    }

    return true;
  }

  @NotNull
  private static String combine(@NotNull String[] strs) {
    final StringBuilder builder = new StringBuilder();

    for (String str : strs) {
      if (builder.length() > 0) {
        builder.append('\n');
      }
      builder.append(str);
    }
    return builder.toString();
  }

  @SuppressWarnings({"BusyWait"})
  private static void forceInterrupt(Thread thread) {
    /*
      ddmlib has incorrect handling of InterruptedException, so we need to invoke it several times,
      because there are three blocking invokation in succession
    */

    for (int i = 0; i < 6 && thread.isAlive(); i++) {
      thread.interrupt();
      try {
        Thread.sleep(200);
      }
      catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private void doInitializeDdmlib() {
    doInitializeDdmlib(getAdbPath());
  }

  private static void doInitializeDdmlib(@NotNull String adbPath) {
    synchronized (myDdmsLock) {
      if (!myDdmLibInitialized) {
        myDdmLibInitialized = true;
        DdmPreferences.setLogLevel(Log.LogLevel.INFO.getStringValue());
        DdmPreferences.setTimeOut(AndroidUtils.TIMEOUT);
        AndroidDebugBridge.init(AndroidEnableAdbServiceAction.isAdbServiceEnabled());
        LOG.info("DDMLib initialized");
        final AndroidDebugBridge bridge = AndroidDebugBridge.createBridge(adbPath, true);
        waitUntilConnect(bridge);
        if (!bridge.isConnected()) {
          LOG.info("Failed to connect debug bridge");
        }
      }
      else {
        final AndroidDebugBridge bridge = AndroidDebugBridge.getBridge();
        final boolean forceRestart = myAdbCrashed || (bridge != null && !bridge.isConnected());
        if (forceRestart) {
          LOG.info("Restart debug bridge: " + (myAdbCrashed ? "crashed" : "disconnected"));
        }
        final AndroidDebugBridge newBridge = AndroidDebugBridge.createBridge(adbPath, forceRestart);
        waitUntilConnect(newBridge);
        if (!newBridge.isConnected()) {
          LOG.info("Failed to connect debug bridge after restart");
        }
      }
    }
  }

  private static void waitUntilConnect(@NotNull AndroidDebugBridge bridge) {
    while (!bridge.isConnected() && !Thread.currentThread().isInterrupted()) {
      try {
        //noinspection BusyWait
        Thread.sleep(1000);
      }
      catch (InterruptedException e) {
        LOG.debug(e);
        return;
      }
    }
  }

  private String getAdbPath() {
    String path = getLocation() + File.separator + SdkConstants.OS_SDK_PLATFORM_TOOLS_FOLDER + SdkConstants.FN_ADB;
    if (!new File(path).exists()) {
      path = getLocation() + File.separator + AndroidCommonUtils.toolPath(SdkConstants.FN_ADB);
    }
    try {
      return new File(path).getCanonicalPath();
    }
    catch (IOException e) {
      LOG.info(e);
      return path;
    }
  }

  public static void terminateDdmlib() {
    synchronized (myDdmsLock) {
      AndroidDebugBridge.disconnectBridge();
      AndroidDebugBridge.terminate();
      LOG.info("DDMLib terminated");
      myDdmLibInitialized = false;
    }
  }

  @Nullable
  public AndroidDebugBridge getDebugBridge(@NotNull Project project) {
    if (!initializeDdmlib(project)) {
      return null;
    }
    return AndroidDebugBridge.getBridge();
  }

  @NotNull
  public LocalSdk getLocalSdk() {
    return myLocalSdk;
  }

  @NotNull
  public DeviceManager getDeviceManager() {
    return myDeviceManager;
  }

  @NotNull
  public AndroidTargetData getTargetData(@NotNull IAndroidTarget target) {
    final SoftReference<AndroidTargetData> targetDataRef = myTargetDatas.get(target);
    AndroidTargetData targetData = targetDataRef != null ? targetDataRef.get() : null;
    if (targetData == null) {
      targetData = new AndroidTargetData(this, target);
      myTargetDatas.put(target, new SoftReference<AndroidTargetData>(targetData));
    }
    return targetData;
  }

  private static class MyInitializeDdmlibTask extends Task.Modal {
    private final Object myLock = new Object();
    private volatile boolean myFinished;
    private volatile boolean myCanceled;

    public MyInitializeDdmlibTask(Project project) {
      super(project, "Waiting for ADB", true);
    }

    public boolean isFinished() {
      synchronized (myLock) {
        return myFinished;
      }
    }

    public boolean isCanceled() {
      synchronized (myLock) {
        return myCanceled;
      }
    }

    public void finish() {
      synchronized (myLock) {
        myFinished = true;
        myLock.notifyAll();
      }
    }

    @Override
    public void onCancel() {
      synchronized (myLock) {
        myCanceled = true;
        myLock.notifyAll();
      }
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
      indicator.setIndeterminate(true);
      synchronized (myLock) {
        final long startTime = System.currentTimeMillis();

        final long timeout = 10000;

        while (!myFinished && !myCanceled && !indicator.isCanceled()) {
          long wastedTime = System.currentTimeMillis() - startTime;
          if (wastedTime >= timeout) {
            break;
          }
          try {
            myLock.wait(Math.min(timeout - wastedTime, 500));
          }
          catch (InterruptedException e) {
            break;
          }
        }
      }
    }
  }
}
