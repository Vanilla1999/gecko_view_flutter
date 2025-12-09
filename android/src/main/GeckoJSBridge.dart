typedef JsHandler = FutureOr<dynamic> Function(List<dynamic> args);

class GeckoJsBridge {
  final _handlers = <String, JsHandler>{};

  void addJavaScriptHandler({
    required String handlerName,
    required JsHandler callback,
  }) {
    _handlers[handlerName] = callback;
  }

  Future<dynamic> handleJsCall(String handlerName, List<dynamic> args) async {
    final handler = _handlers[handlerName];
    if (handler == null) {
      throw Exception('Handler not found: $handlerName');
    }
    return await handler(args);
  }
}