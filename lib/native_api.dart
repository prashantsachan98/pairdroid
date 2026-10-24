import 'dart:convert';
import 'package:flutter/services.dart';

typedef Json = Map<String, dynamic>;

class NativeApi {
  const NativeApi();
  static const channel = MethodChannel('org.pairdroid/native');
  Future<Json> call(String method, [Json args = const {}]) async {
    final result = await channel.invokeMethod<String>(method, jsonEncode(args));
    return jsonDecode(result ?? '{}') as Json;
  }

  Future<Json> rpc(Json target, String command, [Json payload = const {}]) =>
      call('rpc', {'target': target, 'cmd': command, 'payload': payload});
}

String errorText(Object e) =>
    e is PlatformException ? e.message ?? e.code : e.toString();
String durationText(num milliseconds) {
  final minutes = milliseconds ~/ 60000;
  return minutes >= 60 ? '${minutes ~/ 60}h ${minutes % 60}m' : '${minutes}m';
}

String timeText(dynamic timestamp) => timestamp is num
    ? DateTime.fromMillisecondsSinceEpoch(
        timestamp.toInt(),
      ).toLocal().toString().substring(0, 19)
    : 'Unavailable';

class PairingInput {
  static Json parse(String host, String port, String pin, String code) {
    final parts = host.trim().split('.');
    if (parts.length != 4 ||
        parts.any(
          (p) =>
              int.tryParse(p) == null || int.parse(p) < 0 || int.parse(p) > 255,
        )) {
      throw const FormatException('Enter a local IPv4 address');
    }
    final n = int.tryParse(port.trim());
    if (n == null || n < 1024 || n > 65535) {
      throw const FormatException('Port must be 1024–65535');
    }
    final fingerprint = pin.replaceAll(RegExp(r'[\s:]'), '').toLowerCase();
    if (!RegExp(r'^[a-f0-9]{64}$').hasMatch(fingerprint)) {
      throw const FormatException(
        'Enter the full child fingerprint (64 hex characters)',
      );
    }
    if (!RegExp(r'^\d{6}$').hasMatch(code.trim())) {
      throw const FormatException('Enter the six-digit child code');
    }
    return {'host': host.trim(), 'port': n, 'pin': fingerprint};
  }
}
