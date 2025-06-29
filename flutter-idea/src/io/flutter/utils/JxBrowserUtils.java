/*
 * Copyright 2020 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.system.CpuArch;
import io.flutter.settings.FlutterSettings;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.Properties;

public class JxBrowserUtils {
  private static final String JXBROWSER_FILE_PREFIX = "jxbrowser";
  private static final String JXBROWSER_FILE_VERSION = "8.9.0";
  private static final String JXBROWSER_FILE_SUFFIX = "jar";
  public static final String LICENSE_PROPERTY_NAME = "jxbrowser.license.key";

  public String getPlatformFileName() throws FileNotFoundException {
    String name = "";
    final boolean is64Bit = Objects.requireNonNull(CpuArch.CURRENT).width == 64;
    if (SystemInfo.isMac) {
      if (SystemInfo.isAarch64){
        name = "mac-arm";
      } else {
        name = "mac";
      }
    } else if (SystemInfo.isWindows) {
      if (CpuArch.is32Bit()) {
        name = "win32";
      } else if (is64Bit) {
        name = "win64";
      }
    } else if (SystemInfo.isLinux && is64Bit) {
      name = "linux64";
    }

    if (name.isEmpty()) {
      throw new FileNotFoundException("Unable to find matching JxBrowser platform file for: " + SystemInfo.getOsNameAndVersion());
    }

    return String.format("%s-%s-%s.%s", JXBROWSER_FILE_PREFIX, name, JXBROWSER_FILE_VERSION, JXBROWSER_FILE_SUFFIX);
  }

  public String getApiFileName() {
    return String.format("%s-%s.%s", JXBROWSER_FILE_PREFIX, JXBROWSER_FILE_VERSION, JXBROWSER_FILE_SUFFIX);
  }

  public String getSwingFileName() {
    return String.format("%s-swing-%s.%s", JXBROWSER_FILE_PREFIX, JXBROWSER_FILE_VERSION, JXBROWSER_FILE_SUFFIX);
  }

  @NotNull
  public String getDistributionLink(@NotNull String fileName) {
    String envBaseUrl = java.lang.System.getenv("FLUTTER_STORAGE_BASE_URL");
    String baseUrl = envBaseUrl == null ? "https://storage.googleapis.com" : envBaseUrl;
    return String.format("%s/flutter_infra_release/flutter/intellij/jxbrowser/%s", baseUrl, fileName);
  }

  @NotNull
  public String getJxBrowserKey() throws FileNotFoundException {
    if (JxBrowserUtils.class.getResource("/jxbrowser/jxbrowser.properties") == null) {
      throw new FileNotFoundException("jxbrowser.properties file does not exist");
    }

    final Properties properties = new Properties();
    try {
      properties.load(JxBrowserUtils.class.getResourceAsStream("/jxbrowser/jxbrowser.properties"));
    }
    catch (IOException ex) {
      throw new FileNotFoundException("Unable to load properties of JxBrowser key file");
    }

    final String value = properties.getProperty(LICENSE_PROPERTY_NAME);
    if (value == null) {
      throw new FileNotFoundException("No value for JxBrowser key exists");
    }

    return value;
  }

  public boolean licenseIsSet() {
    return System.getProperty(JxBrowserUtils.LICENSE_PROPERTY_NAME) != null;
  }


  public boolean skipInstallation() {
    return FlutterSettings.getInstance().isEnableJcefBrowser();
  }
}
