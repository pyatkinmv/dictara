import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:dictara_web/app.dart';
import 'support/fake_transcription_repository.dart';
import 'package:dictara_web/data/transcription_repository.dart';
import 'package:dictara_web/models/transcription.dart';
import 'package:dictara_web/models/upload.dart';
import 'package:dictara_web/state/transcription_list_controller.dart';
import 'package:dictara_web/widgets/button_spinner.dart';
import 'package:dictara_web/widgets/transcription_row.dart';

import 'test_utils.dart';

/// The mock, with hooks to watch and hold back the requests of later pages.
class _ControlledRepo extends FakeTranscriptionRepository {
  final List<({int page, int size})> calls = [];

  /// When set, requests for page 1 and later wait for it.
  Completer<void>? gate;

  /// When set, requests for page 1 and later fail with it.
  Object? failLaterPages;

  /// Extra items to put in front of a later page, to imitate the list shifting under the reader.
  bool repeatLastItemOnLaterPages = false;

  @override
  Future<TranscriptionPage> list(TranscriptionQuery query, {int page = 0, int size = transcriptionPageSize}) async {
    calls.add((page: page, size: size));
    if (page > 0) {
      await gate?.future;
      if (failLaterPages != null) throw failLaterPages!;
      if (repeatLastItemOnLaterPages) {
        // Page N starts one item early, as it does when something new was added on top meanwhile.
        final shifted = await super.list(query, page: 0, size: page * size + size);
        final all = shifted.items;
        final start = page * size - 1;
        return TranscriptionPage(all.sublist(start, all.length), hasNext: shifted.hasNext);
      }
    }
    return super.list(query, page: page, size: size);
  }
}

/// The seven transcriptions of the design plus [uploads] more, each newer than the last (ids 7…).
Future<_ControlledRepo> _repoWith(int uploads) async {
  final repo = _ControlledRepo();
  for (var i = 0; i < uploads; i++) {
    await repo.upload(UploadRequest(file: ChosenFile(name: 'u$i.mp3', sizeBytes: 1024 * 1024)));
  }
  return repo;
}

List<String> _ids(TranscriptionListController c) => [for (final i in c.items) i.id];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(loadInter);
  _searchDebounceTests();

  group('FakeTranscriptionRepository pages', () {
    test('a page is a slice of the sorted list and knows whether more follows', () async {
      final repo = FakeTranscriptionRepository();

      final first = await repo.list(const TranscriptionQuery(), size: 3);
      final second = await repo.list(const TranscriptionQuery(), page: 1, size: 3);
      final third = await repo.list(const TranscriptionQuery(), page: 2, size: 3);

      expect(first.items.map((i) => i.id), ['6', '5', '4']);
      expect(first.hasNext, isTrue);
      expect(second.items.map((i) => i.id), ['3', '2', '1']);
      expect(second.hasNext, isTrue);
      expect(third.items.map((i) => i.id), ['0']);
      expect(third.hasNext, isFalse);
    });

    test('a page that ends exactly with the list has no next page', () async {
      final repo = FakeTranscriptionRepository();
      expect((await repo.list(const TranscriptionQuery(), size: 7)).hasNext, isFalse);
      expect((await repo.list(const TranscriptionQuery(), size: 6)).hasNext, isTrue);
    });

    test('past the end is empty', () async {
      final page = await FakeTranscriptionRepository().list(const TranscriptionQuery(), page: 4, size: 3);
      expect(page.items, isEmpty);
      expect(page.hasNext, isFalse);
    });

    test('filters and sort come before paging', () async {
      final repo = FakeTranscriptionRepository();

      final done = await repo.list(const TranscriptionQuery(statuses: {TranscriptionStatus.done}), size: 2);
      expect(done.items.map((i) => i.id), ['6', '5']);
      expect(done.hasNext, isTrue); // 5 done

      final oldest = await repo.list(const TranscriptionQuery(sort: TranscriptionSort.oldest), size: 2);
      expect(oldest.items.map((i) => i.id), ['0', '1']);
    });
  });

  group('TranscriptionListController paging', () {
    Future<TranscriptionListController> controller(_ControlledRepo repo, {int pageSize = 10}) async {
      final c = TranscriptionListController(repo, pageSize: pageSize, searchDebounce: Duration.zero);
      addTearDown(c.dispose);
      await pumpEventQueue();
      return c;
    }

    test('starts with one page and knows there is more', () async {
      final repo = await _repoWith(58); // 65 in all
      final c = await controller(repo);

      expect(c.items.length, 10);
      expect(c.hasMore, isTrue);
      expect(c.loading, isFalse);
      expect(_ids(c).first, '64'); // the newest
      expect(repo.calls.single, (page: 0, size: 10));
    });

    test('loadMore appends the next page, in order, until the list ends', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);

      for (var pages = 2; pages <= 6; pages++) {
        await c.loadMore();
        expect(c.items.length, pages * 10, reason: 'after $pages pages');
        expect(c.hasMore, isTrue);
      }
      await c.loadMore(); // 65 items: the last page has 5
      expect(c.items.length, 65);
      expect(c.hasMore, isFalse);
      expect(_ids(c), [for (var i = 64; i >= 0; i--) '$i']);
    });

    test('once everything is loaded loadMore asks for nothing', () async {
      final repo = await _repoWith(3); // 10 in all: exactly one page
      final c = await controller(repo);
      expect(c.hasMore, isFalse);

      await c.loadMore();

      expect(repo.calls.length, 1);
    });

    test('loadMore while a page is on its way does not ask twice', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      repo.gate = Completer<void>();

      final first = c.loadMore();
      final second = c.loadMore();
      expect(c.loadingMore, isTrue);
      repo.gate!.complete();
      await Future.wait([first, second]);

      expect(repo.calls.where((call) => call.page > 0).length, 1);
      expect(c.items.length, 20);
      expect(c.loadingMore, isFalse);
    });

    test('a new query starts over with the first page', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      await c.loadMore();
      await c.loadMore();
      expect(c.items.length, 30);

      c.setSearch('u'); // matches the uploads u0…u57
      await pumpEventQueue();

      expect(c.items.length, 10);
      expect(c.items.every((i) => i.title.contains('u')), isTrue);
      expect(repo.calls.last, (page: 0, size: 10));
    });

    test('a refresh asks for everything loaded so far, so nothing scrolled is lost', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      await c.loadMore();
      await c.loadMore();

      await c.reload();

      expect(repo.calls.last, (page: 0, size: 30));
      expect(c.items.length, 30);
      expect(c.hasMore, isTrue);
    });

    test('a refresh after an upload moves the list down without losing or repeating anyone', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      await c.loadMore(); // 20 loaded
      await repo.upload(const UploadRequest(file: ChosenFile(name: 'late.mp3', sizeBytes: 1024 * 1024)));

      await c.reload();
      expect(_ids(c).first, '65'); // the new one is on top
      expect(c.items.length, 20);
      await c.loadMore();

      // Everything from the newest to the 30th: consecutive, no gaps and no duplicates.
      expect(_ids(c), [for (var i = 65; i > 35; i--) '$i']);
    });

    test('an item that shows up on two pages is kept once', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      repo.repeatLastItemOnLaterPages = true;

      await c.loadMore(); // the page starts with the last item of the first one

      expect(_ids(c), [for (var i = 64; i > 44; i--) '$i']);
    });

    test('a failed page stops the automatic loading until retried', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      repo.failLaterPages = Exception('offline');

      await c.loadMore();
      expect(c.loadMoreFailed, isTrue);
      expect(c.loadingMore, isFalse);
      expect(c.items.length, 10);

      final calls = repo.calls.length;
      await c.loadMore(); // what scrolling does again and again
      expect(repo.calls.length, calls, reason: 'no request until the user retries');

      repo.failLaterPages = null;
      await c.loadMore(retry: true);
      expect(c.loadMoreFailed, isFalse);
      expect(c.items.length, 20);
    });

    test('a refresh clears the failure', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      repo.failLaterPages = Exception('offline');
      await c.loadMore();
      expect(c.loadMoreFailed, isTrue);

      await c.reload();

      expect(c.loadMoreFailed, isFalse);
    });

    test('a page that arrives after a new query is thrown away', () async {
      final repo = await _repoWith(58);
      final c = await controller(repo);
      repo.gate = Completer<void>();

      final late = c.loadMore();
      c.setSearch('u1'); // starts over while the second page is still on its way
      await pumpEventQueue();
      final titlesAfterQuery = c.items.map((i) => i.title).toList();
      repo.gate!.complete();
      await late;

      expect(c.items.map((i) => i.title).toList(), titlesAfterQuery);
      expect(c.items.every((i) => i.title.contains('u1')), isTrue);
      expect(c.loadingMore, isFalse);
    });

    test('a refresh never asks for more than the gateway serves', () async {
      final repo = await _repoWith(0);
      final c = TranscriptionListController(repo, pageSize: 300);
      addTearDown(c.dispose);
      await pumpEventQueue();
      expect(repo.calls.first.size, 300);
      await c.reload();
      expect(repo.calls.last.size, lessThanOrEqualTo(transcriptionMaxPageSize));
    });
  });

  group('the list screen', () {
    Future<TranscriptionListController> openList(WidgetTester tester, _ControlledRepo repo) async {
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo));
      await settle(tester);
      return Provider.of<TranscriptionListController>(tester.element(find.byType(Scaffold)), listen: false);
    }

    Future<void> scrollToEnd(WidgetTester tester) async {
      for (var i = 0; i < 12; i++) {
        await tester.drag(find.byType(CustomScrollView), const Offset(0, -2500));
        await settle(tester);
      }
    }

    testWidgets('shows the first page and loads the next ones while scrolling to the end', (tester) async {
      final repo = await _repoWith(58); // 65 in all, pages of 30
      final list = await openList(tester, repo);
      expect(list.items.length, 30);
      expect(find.text('u57.mp3'), findsOneWidget); // the newest

      await scrollToEnd(tester);

      expect(list.items.length, 65);
      expect(list.hasMore, isFalse);
      // The oldest one, at the very bottom, is reachable.
      expect(find.text('Quarterly Review Meeting'), findsOneWidget);
      expect(repo.calls.map((c) => c.page).where((p) => p > 0), containsAll([1, 2]));
    });

    testWidgets('a short list does not ask for more', (tester) async {
      final repo = _ControlledRepo(); // 7 items
      await openList(tester, repo);

      expect(repo.calls.length, 1);
      expect(find.byType(TranscriptionRow), findsNWidgets(7));
    });

    testWidgets('a spinner shows under the list while the next page loads', (tester) async {
      final repo = await _repoWith(58);
      final list = await openList(tester, repo);
      repo.gate = Completer<void>();
      expect(find.byType(ButtonSpinner), findsNothing);

      await tester.drag(find.byType(CustomScrollView), const Offset(0, -2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(list.loadingMore, isTrue);
      expect(find.byType(ButtonSpinner), findsOneWidget);

      repo.gate!.complete();
      await settle(tester);
      expect(find.byType(ButtonSpinner), findsNothing);
    });

    testWidgets('a page that fails offers to try again, and trying again works', (tester) async {
      final repo = await _repoWith(58);
      final list = await openList(tester, repo);
      repo.failLaterPages = Exception('offline');

      await scrollToEnd(tester);
      expect(list.loadMoreFailed, isTrue);
      expect(find.text("Couldn't load more. Try again"), findsOneWidget);

      repo.failLaterPages = null;
      await tester.tap(find.text("Couldn't load more. Try again"));
      await settle(tester);
      await scrollToEnd(tester);

      expect(find.text("Couldn't load more. Try again"), findsNothing);
      expect(list.items.length, 65);
    });

    testWidgets('changing the search starts again from the top with the first page', (tester) async {
      final repo = await _repoWith(58);
      final list = await openList(tester, repo);
      await scrollToEnd(tester);
      expect(list.items.length, 65);
      for (var i = 0; i < 12; i++) {
        await tester.drag(find.byType(CustomScrollView), const Offset(0, 2500)); // back to the search field
        await settle(tester);
      }

      await tester.enterText(find.byType(TextField).first, 'u5');
      await settle(tester);

      // u5 and u50..u57: nine, all on the first page.
      expect(list.items.length, 9);
      expect(list.hasMore, isFalse);
    });
  });
}

/// The search box: the list follows once typing pauses, not on every letter.
void _searchDebounceTests() {
  group('search debounce', () {
    Future<(_ControlledRepo, TranscriptionListController)> start(WidgetTester tester, {Duration? debounce}) async {
      final repo = await _repoWith(0);
      final c = debounce == null
          ? TranscriptionListController(repo)
          : TranscriptionListController(repo, searchDebounce: debounce);
      addTearDown(c.dispose);
      await tester.pump(); // the first load
      return (repo, c);
    }

    testWidgets('letters typed in a burst cause one request, after the pause', (tester) async {
      final (repo, c) = await start(tester);
      final before = repo.calls.length;

      c.setSearch('r');
      c.setSearch('re');
      c.setSearch('rev');
      expect(c.query.search, 'rev'); // the field has it at once
      await tester.pump(const Duration(milliseconds: 200));
      expect(repo.calls.length, before, reason: 'still waiting for more typing');
      await tester.pump(const Duration(milliseconds: 100));

      expect(repo.calls.length, before + 1);
      expect(repo.calls.last, (page: 0, size: transcriptionPageSize));
    });

    testWidgets('every letter restarts the wait', (tester) async {
      final (repo, c) = await start(tester);
      final before = repo.calls.length;

      c.setSearch('a');
      await tester.pump(const Duration(milliseconds: 200));
      c.setSearch('ab'); // just before the first would have fired
      await tester.pump(const Duration(milliseconds: 200));
      expect(repo.calls.length, before);
      await tester.pump(const Duration(milliseconds: 100));

      expect(repo.calls.length, before + 1);
    });

    testWidgets('a pause between two bursts gives two requests', (tester) async {
      final (repo, c) = await start(tester);
      final before = repo.calls.length;

      c.setSearch('a');
      await tester.pump(const Duration(milliseconds: 300));
      c.setSearch('ab');
      await tester.pump(const Duration(milliseconds: 300));

      expect(repo.calls.length, before + 2);
    });

    testWidgets('a filter chosen while typing sends at once, with the search text', (tester) async {
      final (repo, c) = await start(tester);
      final before = repo.calls.length;

      c.setSearch('rev');
      c.toggleStatus(TranscriptionStatus.done);
      await tester.pump();
      expect(repo.calls.length, before + 1);
      expect(c.query.search, 'rev');
      expect(c.query.statuses, {TranscriptionStatus.done});

      await tester.pump(const Duration(milliseconds: 400));
      expect(repo.calls.length, before + 1, reason: 'the waiting search request was replaced, not repeated');
    });

    testWidgets('a sort chosen while typing does the same', (tester) async {
      final (repo, c) = await start(tester);
      final before = repo.calls.length;

      c.setSearch('rev');
      c.setSort(TranscriptionSort.name);
      await tester.pump(const Duration(milliseconds: 400));

      expect(repo.calls.length, before + 1);
    });

    testWidgets('nothing is sent after the controller is gone', (tester) async {
      final repo = await _repoWith(0);
      final c = TranscriptionListController(repo);
      await tester.pump();
      final before = repo.calls.length;

      c.setSearch('rev');
      c.dispose();
      await tester.pump(const Duration(milliseconds: 400));

      expect(repo.calls.length, before);
    });

    testWidgets('a zero debounce sends at once', (tester) async {
      final (repo, c) = await start(tester, debounce: Duration.zero);
      final before = repo.calls.length;

      c.setSearch('rev');
      await tester.pump();

      expect(repo.calls.length, before + 1);
    });

    testWidgets('typing in the search box searches once, after the pause', (tester) async {
      final repo = await _repoWith(0);
      tester.view.physicalSize = const Size(1280, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(DictaraApp(authController: signedInAuthController(), repository: repo));
      await settle(tester);
      final before = repo.calls.length;

      await tester.enterText(find.byType(TextField).first, 'standup');
      await tester.pump(const Duration(milliseconds: 100));
      expect(repo.calls.length, before, reason: 'not yet');
      await tester.pump(const Duration(milliseconds: 300));

      expect(repo.calls.length, before + 1);
      expect(find.byType(TranscriptionRow), findsOneWidget); // the mock filters by title
    });
  });
}
