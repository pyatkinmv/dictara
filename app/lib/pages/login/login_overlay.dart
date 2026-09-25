import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/external_link.dart';
import '../../state/auth_controller.dart';
import '../../theme/app_palette.dart';
import '../../widgets/button_spinner.dart';

/// Blocks the whole window behind a blurred, dimmed backdrop and shows the sign-in card while nobody is
/// signed in (`app.dart` shows this, on top of everything else, whenever `AuthController.isLoggedIn` is
/// false). There is no way to dismiss it: nothing in the app works without a session, so a Cancel button
/// would only leave the window in a state where nothing can be done anyway.
class LoginOverlay extends StatefulWidget {
  const LoginOverlay({super.key, this.linkOpener = const BrowserExternalLinkOpener()});

  final ExternalLinkOpener linkOpener;

  @override
  State<LoginOverlay> createState() => _LoginOverlayState();
}

class _LoginOverlayState extends State<LoginOverlay> {
  final _usernameController = TextEditingController();

  @override
  void dispose() {
    _usernameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthController>();
    return Stack(
      children: [
        // Blurs and dims whatever is painted behind this overlay in the Stack `app.dart` puts it in (the real
        // app content) — the same technique `widgets/modal.dart` uses for its dialogs. A plain Container with a
        // color blocks pointer events by default, so nothing behind it is reachable either.
        Positioned.fill(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 7, sigmaY: 7),
            child: Container(color: const Color(0xFF0F2922).withValues(alpha: .42)),
          ),
        ),
        Center(
          child: Material(
            type: MaterialType.transparency,
            child: _LoginCard(auth: auth, usernameController: _usernameController, onOpenBot: widget.linkOpener.open),
          ),
        ),
      ],
    );
  }
}

class _LoginCard extends StatelessWidget {
  const _LoginCard({required this.auth, required this.usernameController, required this.onOpenBot});

  final AuthController auth;
  final TextEditingController usernameController;
  final void Function(String url) onOpenBot;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return ConstrainedBox(
      constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * .95),
      child: Container(
        width: 440,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: p.surface,
          borderRadius: BorderRadius.circular(20),
          boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: .25), blurRadius: 60, offset: const Offset(0, 20))],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _Header(p: p),
            Padding(
              padding: const EdgeInsets.fromLTRB(32, 28, 32, 32),
              child: switch (auth.stage) {
                LoginStage.input => _InputStage(auth: auth, controller: usernameController),
                LoginStage.notified => _NotifiedStage(auth: auth),
                LoginStage.unknownUser => _UnknownStage(auth: auth, onOpenBot: onOpenBot),
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// The branded band at the top of the card: logo, name, tagline — the same in every stage, room for other
/// sign-in providers to sit below it later without redesigning the card.
class _Header extends StatelessWidget {
  const _Header({required this.p});

  final AppPalette p;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(32, 28, 32, 22),
      decoration: BoxDecoration(color: p.accentLight, border: Border(bottom: BorderSide(color: p.border))),
      child: Column(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(color: p.accent, shape: BoxShape.circle),
            child: const Icon(Icons.mic_rounded, color: Colors.white, size: 22),
          ),
          const SizedBox(height: 10),
          Text('Dictara', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: p.accentDark)),
          const SizedBox(height: 4),
          Text(
            'Turn recordings into searchable transcripts, automatically.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12, color: p.text2, height: 1.4),
          ),
        ],
      ),
    );
  }
}

class _InputStage extends StatelessWidget {
  const _InputStage({required this.auth, required this.controller});

  final AuthController auth;
  final TextEditingController controller;

  void _submit() => auth.submitUsername(controller.text);

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Continue with your Telegram account',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: p.text),
        ),
        const SizedBox(height: 14),
        Text('Telegram username', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: p.text2)),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(border: Border.all(color: p.border), borderRadius: BorderRadius.circular(10)),
          child: Row(
            children: [
              Text('@', style: TextStyle(fontSize: 14, color: p.text3)),
              Expanded(
                child: TextField(
                  key: const Key('login-username-field'),
                  controller: controller,
                  autofocus: true,
                  enabled: !auth.busy,
                  decoration: const InputDecoration(
                    hintText: 'username',
                    border: InputBorder.none,
                    isDense: true,
                    contentPadding: EdgeInsets.symmetric(vertical: 9, horizontal: 6),
                  ),
                  style: TextStyle(fontSize: 14, color: p.text),
                  onSubmitted: (_) => _submit(),
                ),
              ),
            ],
          ),
        ),
        if (auth.error != null) ...[
          const SizedBox(height: 10),
          Text(auth.error!, style: TextStyle(fontSize: 12.5, color: p.danger)),
        ],
        const SizedBox(height: 14),
        _PrimaryButton(busy: auth.busy, onTap: _submit),
        const SizedBox(height: 14),
        _OrDivider(p: p),
        const SizedBox(height: 14),
        _AnonymousLink(busy: auth.busy, onTap: auth.continueAnonymously),
        const SizedBox(height: 10),
        Text(
          'More sign-in options — including Google — are coming soon.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 11, color: p.text3),
        ),
      ],
    );
  }
}

class _OrDivider extends StatelessWidget {
  const _OrDivider({required this.p});

  final AppPalette p;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Divider(color: p.border)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10),
          child: Text('or', style: TextStyle(fontSize: 11, color: p.text3)),
        ),
        Expanded(child: Divider(color: p.border)),
      ],
    );
  }
}

class _AnonymousLink extends StatefulWidget {
  const _AnonymousLink({required this.busy, required this.onTap});

  final bool busy;
  final VoidCallback onTap;

  @override
  State<_AnonymousLink> createState() => _AnonymousLinkState();
}

class _AnonymousLinkState extends State<_AnonymousLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: widget.busy ? MouseCursor.defer : SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.busy ? null : widget.onTap,
        child: Container(
          key: const Key('login-anonymous-link'),
          width: double.infinity,
          alignment: Alignment.center,
          child: Text(
            'Continue without an account',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: p.text2,
              decoration: _hover ? TextDecoration.underline : TextDecoration.none,
            ),
          ),
        ),
      ),
    );
  }
}

class _PrimaryButton extends StatelessWidget {
  const _PrimaryButton({required this.busy, required this.onTap});

  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: busy ? null : onTap,
        child: Ink(
          padding: const EdgeInsets.symmetric(vertical: 11),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [p.accent, p.accentDark],
            ),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Align(
            child: busy
                ? const ButtonSpinner(color: Colors.white, size: 16)
                : const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.send_rounded, color: Colors.white, size: 17),
                      SizedBox(width: 8),
                      Text('Continue with Telegram', style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600)),
                    ],
                  ),
          ),
        ),
      ),
    );
  }
}

class _NotifiedStage extends StatelessWidget {
  const _NotifiedStage({required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          width: 28,
          height: 28,
          child: CircularProgressIndicator(strokeWidth: 3, color: p.accent, backgroundColor: p.border),
        ),
        const SizedBox(height: 16),
        Text('Check your Telegram', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: p.text)),
        const SizedBox(height: 8),
        Text.rich(
          textAlign: TextAlign.center,
          TextSpan(
            style: TextStyle(fontSize: 13, color: p.text2, height: 1.5),
            children: [
              const TextSpan(text: 'We sent a confirmation to '),
              TextSpan(text: '@${auth.username}', style: TextStyle(color: p.text, fontWeight: FontWeight.w700)),
              const TextSpan(text: '. Tap '),
              TextSpan(text: 'Confirm', style: TextStyle(color: p.text, fontWeight: FontWeight.w700)),
              const TextSpan(text: ' there to finish signing in.'),
            ],
          ),
        ),
        const SizedBox(height: 16),
        _BackLink(label: '← Use a different username', onTap: auth.reset),
      ],
    );
  }
}

class _UnknownStage extends StatelessWidget {
  const _UnknownStage({required this.auth, required this.onOpenBot});

  final AuthController auth;
  final void Function(String url) onOpenBot;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final botUrl = auth.botUrl;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          "We couldn't find you on Telegram",
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: p.text),
        ),
        const SizedBox(height: 8),
        Text(
          'Start a chat with our bot, then come back here.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 13, color: p.text2, height: 1.5),
        ),
        const SizedBox(height: 14),
        if (botUrl != null) _OpenBotLink(url: botUrl, onOpen: onOpenBot),
        const SizedBox(height: 10),
        Text(
          'Send /start, then tap Confirm in the message it sends.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 12, color: p.text3, height: 1.4),
        ),
        const SizedBox(height: 12),
        Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2, color: p.accent, backgroundColor: p.border),
            ),
            const SizedBox(width: 8),
            Text('Waiting for confirmation…', style: TextStyle(fontSize: 12, color: p.text3)),
          ],
        ),
        const SizedBox(height: 12),
        _BackLink(label: '← Try a different username', onTap: auth.reset),
      ],
    );
  }
}

class _OpenBotLink extends StatefulWidget {
  const _OpenBotLink({required this.url, required this.onOpen});

  final String url;
  final void Function(String url) onOpen;

  @override
  State<_OpenBotLink> createState() => _OpenBotLinkState();
}

class _OpenBotLinkState extends State<_OpenBotLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    final segments = Uri.tryParse(widget.url)?.pathSegments ?? const <String>[];
    final label = 'Open ${segments.isEmpty ? 'the bot' : '@${segments.last}'} in Telegram →';
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: () => widget.onOpen(widget.url),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: _hover ? const Color(0xFFDFFBF3) : p.accentLight,
            borderRadius: BorderRadius.circular(10),
          ),
          alignment: Alignment.center,
          child: Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: p.accent)),
        ),
      ),
    );
  }
}

class _BackLink extends StatefulWidget {
  const _BackLink({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  State<_BackLink> createState() => _BackLinkState();
}

class _BackLinkState extends State<_BackLink> {
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final p = context.palette;
    return MouseRegion(
      cursor: SystemMouseCursors.click,
      onEnter: (_) => setState(() => _hover = true),
      onExit: (_) => setState(() => _hover = false),
      child: GestureDetector(
        onTap: widget.onTap,
        child: Text(
          widget.label,
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: p.accent,
            decoration: _hover ? TextDecoration.underline : TextDecoration.none,
          ),
        ),
      ),
    );
  }
}
