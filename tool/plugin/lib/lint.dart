// Copyright 2017 The Chromium Authors. All rights reserved. Use of this source
// code is governed by a BSD-style license that can be found in the LICENSE file.

import 'dart:async';
import 'dart:io';

import 'package:args/command_runner.dart';

import 'runner.dart';
import 'util.dart';

class LintCommand extends Command<int> {
  @override
  final BuildCommandRunner runner;

  LintCommand(this.runner);

  @override
  String get name => 'lint';

  @override
  String get description =>
      'Perform simple validations on the flutter-intellij repo code.';

  @override
  Future<int> run() async {
    // Check for unintentionally imported annotations.
    if (checkForBadImports()) {
      return 1;
    }

    // Print a report for the API used from the Dart plugin.
    printApiUsage();

    return 0;
  }

  void printApiUsage() {
    separator('Dart plugin API usage');

    final result = Process.runSync(
      'git',
      // Note: extra quotes added so grep doesn't match this file.
      [
        'grep',
        'import com.jetbrains.'
            'lang.dart.',
      ],
    );
    final String imports = (result.stdout as String).trim();

    // path:import
    final usages = <String, List<String>>{};

    imports.split('\n').forEach((String line) {
      if (line.trim().isEmpty) {
        return;
      }

      var index = line.indexOf(':');
      var place = line.substring(0, index);
      var import = line.substring(index + 1);
      if (import.startsWith('import ')) import = import.substring(7);
      if (import.endsWith(';')) import = import.substring(0, import.length - 1);
      usages.putIfAbsent(import, () => []);
      usages[import]!.add(place);
    });

    // print report
    final keys = usages.keys.toList();
    keys.sort();

    print('${keys.length} separate Dart plugin APIs used:');
    print('------');

    for (var import in keys) {
      print('$import:');
      var places = usages[import];
      for (var place in places!) {
        print('  $place');
      }
      print('');
    }
  }

  /// Return `true` if an import violation was found.
  bool checkForBadImports() {
    separator('Check for bad imports');

    final proscribedImports = [
      'com.android.annotations.NonNull',
      'io.netty.',
      'javax.annotation.Nullable',
      // org.apache.commons.lang.StringUtils and
      // org.apache.commons.lang.StringEscapeUtils are being deprecated,
      // use org.apache.commons.lang3.* instead.
      // See https://github.com/flutter/flutter-intellij/issues/6933
      'org.apache.commons.lang.StringUtils',
      'org.apache.commons.lang.StringEscapeUtils',
      // https://github.com/dart-lang/sdk/issues/39377
      'org.apache.commons.lang3.StringUtils',
      // org.apache.commons.lang3.StringEscapeUtils is deprecated
      'org.apache.commons.lang3.StringEscapeUtils',

      // Not technically a bad import, but not all IntelliJ platforms provide
      // this library.
      'org.apache.commons.io.',

      // gnu.trove. classes seem to all be deprecated, avoid into the future
      'gnu.trove.',

      // Prefer to use built-in Java collections or fastutil.
      'com.android.tools.idea.io.netty.util.collection.',

      // Internal APIs that shouldn't be used by 3p plugins:
      'com.intellij.util.PlatformUtils',
    ];

    for (var import in proscribedImports) {
      print('Checking for import of "$import"...');

      final result = Process.runSync('git', ['grep', 'import $import']);

      final String results = (result.stdout as String).trim();
      if (results.isNotEmpty) {
        print('Found proscribed imports:\n');
        print(results);
        return true;
      } else {
        print('  none found');
      }
    }

    final partialImports = ['com.sun.'];

    for (var import in partialImports) {
      print('Checking for import of "$import"...');

      final result = Process.runSync('git', ['grep', 'import $import']);

      final String results = (result.stdout as String).trim();
      if (results.isNotEmpty) {
        print('Found proscribed imports:\n');
        print(results);
        return true;
      } else {
        print('  none found');
      }
    }

    return false;
  }
}
