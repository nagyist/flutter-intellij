/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.preview;

import com.intellij.openapi.util.text.StringUtil;
import org.dartlang.analysis.server.protocol.Element;
import org.jetbrains.annotations.NotNull;

public class ModelUtils {
  private ModelUtils() {
  }

  public static boolean isBuildMethod(@NotNull Element element) {
    if (element.getName() == null || element.getParameters() == null) {
      return false;
    }
    return StringUtil.equals("build", element.getName()) &&
           StringUtil.startsWith(element.getParameters(), "(BuildContext ");
  }
}
