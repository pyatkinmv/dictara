import 'dart:convert';

import 'package:dictara_web/data/gateway_client.dart';
import 'package:dictara_web/data/mcp_tokens_api.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response _json(Object body, {int status = 200}) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': 'application/json; charset=utf-8'});

McpTokensApi _api(Future<http.Response> Function(http.Request) handler, [List<http.Request>? seen]) =>
    HttpMcpTokensApi(GatewayClient(httpClient: MockClient((request) async {
      seen?.add(request);
      return handler(request);
    })));

void main() {
  const entry = {
    'id': '7c1e0000-0000-4000-8000-000000000001',
    'name': 'OpenCode on laptop',
    'prefix': 'dtr_Zk3p',
    'access': 'read',
    'status': 'active',
    'created_at': '2026-10-20T10:00:00Z',
    'expires_at': '2027-01-18T10:00:00Z',
  };

  test('the list is read from /mcp-tokens: status, access, dates in local time, last use only when there was one', () async {
    final seen = <http.Request>[];
    final api = _api((_) async => _json({
          'tokens': [
            entry,
            {...entry, 'id': 'b', 'name': 'Old', 'access': 'write', 'status': 'expired', 'last_used_at': '2026-10-21T08:30:00Z'},
            {...entry, 'id': 'c', 'status': 'expiring'},
          ],
        }), seen);

    final tokens = await api.list();

    expect(seen.single.method, 'GET');
    expect(seen.single.url.path, '/api/mcp-tokens');
    expect(tokens.map((t) => t.status), [McpTokenStatus.active, McpTokenStatus.expired, McpTokenStatus.expiring]);
    expect(tokens[1].access, McpAccess.write);
    expect(tokens[0].prefix, 'dtr_Zk3p');
    expect(tokens[0].lastUsedAt, isNull);
    expect(tokens[1].lastUsedAt!.toUtc(), DateTime.utc(2026, 10, 21, 8, 30));
    expect(tokens[0].expiresAt.toUtc(), DateTime.utc(2027, 1, 18, 10));
    expect(tokens[0].expiresAt.isUtc, isFalse);
  });

  test('a status or an access it does not know is treated as working and read-only', () async {
    final api = _api((_) async => _json({
          'tokens': [{...entry, 'status': 'something-new', 'access': 'admin'}],
        }));

    final token = (await api.list()).single;

    expect(token.status, McpTokenStatus.active);
    expect(token.access, McpAccess.read);
  });

  test('an answer that is not a list of tokens is an error', () async {
    await expectLater(_api((_) async => _json({'oops': 1})).list(), throwsA(isA<GatewayException>()));
  });

  test('creating sends the name and the access, and returns the token with the secret shown that once', () async {
    final seen = <http.Request>[];
    final api = _api((_) async => _json({...entry, 'token': 'dtr_${'x' * 43}'}, status: 201), seen);

    final created = await api.create(name: 'OpenCode on laptop', access: McpAccess.write);

    expect(seen.single.method, 'POST');
    expect(seen.single.url.path, '/api/mcp-tokens');
    expect(jsonDecode(seen.single.body), {'name': 'OpenCode on laptop', 'access': 'write'});
    expect(created.secret, 'dtr_${'x' * 43}');
    expect(created.token.name, 'OpenCode on laptop');
    expect(created.token.status, McpTokenStatus.active);
  });

  test('a refusal says why, in the gateway\'s own words', () async {
    final api = _api((_) async => _json({'message': 'You already have 10 working tokens; revoke one first'}, status: 409));

    await expectLater(
      api.create(name: 'x', access: McpAccess.read),
      throwsA(isA<GatewayException>().having((e) => e.message, 'message', contains('revoke one first'))),
    );
  });

  test('revoking is a DELETE of that token', () async {
    final seen = <http.Request>[];
    final api = _api((_) async => http.Response('', 204), seen);

    await api.revoke('7c1e0000-0000-4000-8000-000000000001');

    expect(seen.single.method, 'DELETE');
    expect(seen.single.url.path, '/api/mcp-tokens/7c1e0000-0000-4000-8000-000000000001');
  });
}
