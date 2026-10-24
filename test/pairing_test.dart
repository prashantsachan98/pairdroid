import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pairdroid/native_api.dart';
import 'package:pairdroid/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final pin = 'ab' * 32;
  test('pairing validates address, port, full fingerprint and code', () {
    final target = PairingInput.parse(
      '192.168.1.2',
      '5000',
      pin.toUpperCase(),
      '001234',
    );
    expect(target['pin'], pin);
    expect(target['port'], 5000);
    for (final invalid in ['8.8.8', '192.168.1.999', 'child.local']) {
      expect(
        () => PairingInput.parse(invalid, '5000', pin, '001234'),
        throwsFormatException,
      );
    }
    expect(
      () => PairingInput.parse('192.168.1.2', '0', pin, '001234'),
      throwsFormatException,
    );
    expect(
      () => PairingInput.parse('192.168.1.2', '5000', 'ab', '001234'),
      throwsFormatException,
    );
    expect(
      () => PairingInput.parse('192.168.1.2', '5000', pin, '123'),
      throwsFormatException,
    );
  });
  test('native errors reach caller without fake fallback data', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(NativeApi.channel, (call) async {
          throw PlatformException(
            code: 'native',
            message: 'Unauthorized certificate',
          );
        });
    await expectLater(
      const NativeApi().rpc({'pin': pin}, 'usage'),
      throwsA(isA<PlatformException>()),
    );
  });
  testWidgets(
    'parent has no fake paired devices and opens real pairing workflow',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            NativeApi.channel,
            (call) async => jsonEncode({
              'role': 'parent',
              'name': 'Test phone',
              'android': '16',
              'wifi': true,
              'error': '',
              'fingerprint': pin,
              'peers': [],
              'discovered': [],
            }),
          );
      await tester.pumpWidget(const PairDroidApp());
      await tester.pumpAndSettle();
      expect(find.textContaining('No devices paired yet'), findsOneWidget);
      await tester.ensureVisible(find.text('Pair a device manually'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Pair a device manually'));
      await tester.pumpAndSettle();
      expect(find.text('Child IP address'), findsOneWidget);
      expect(find.text('Full child fingerprint'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
