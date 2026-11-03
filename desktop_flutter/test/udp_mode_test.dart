import 'package:flutter_test/flutter_test.dart';
import 'package:gozar/core.dart';
import 'package:gozar/strings.dart';

void main() {
  group('UDP mode state', () {
    test('defaults to normal with the console master off', () {
      final state = CoreState.fromJson(<String, dynamic>{});
      expect(state.udpMode, 'normal');
      expect(state.batterySaver, isFalse);
      expect(state.consoleOn, isFalse);
      expect(state.udpRelay, isTrue);
    });

    test('reads every supported mode from the core', () {
      for (final mode in ['normal', 'game', 'relay_only']) {
        expect(CoreState.fromJson({'udpMode': mode}).udpMode, mode);
      }
    });

    test('falls back to normal for unknown or malformed values', () {
      for (final raw in <Object?>['', 'warp', 'GAME', null, 7, <String>[]]) {
        expect(CoreState.fromJson({'udpMode': raw}).udpMode, 'normal',
            reason: 'raw value: $raw');
      }
    });

    test('carries the battery saver and console master flags', () {
      final state = CoreState.fromJson(<String, dynamic>{
        'udpMode': 'game',
        'battery_saver': true,
        'console_on': true,
      });
      expect(state.udpMode, 'game');
      expect(state.batterySaver, isTrue);
      expect(state.consoleOn, isTrue);
    });

    test('ignores a non-boolean battery saver', () {
      expect(CoreState.fromJson({'battery_saver': 'yes'}).batterySaver, isFalse);
    });
  });

  group('UDP relay warning', () {
    CoreState state({required bool console, required bool relay, String mode = 'game'}) =>
        CoreState.fromJson(<String, dynamic>{
          'console_on': console,
          'udp_relay': relay,
          'udpMode': mode,
        });

    test('shows when Game Mode is on but UDP Relay is off', () {
      expect(udpRelayWarningNeeded(state(console: true, relay: false)), isTrue);
    });

    test('hidden once UDP Relay is turned on', () {
      expect(udpRelayWarningNeeded(state(console: true, relay: true)), isFalse);
    });

    test('hidden when the console master is off', () {
      expect(udpRelayWarningNeeded(state(console: false, relay: false)), isFalse);
      expect(udpRelayWarningNeeded(state(console: false, relay: true)), isFalse);
    });

    test('shows in every mode, because the relay carries DNS and web UDP', () {
      for (final mode in ['normal', 'game', 'relay_only']) {
        expect(udpRelayWarningNeeded(state(console: true, relay: false, mode: mode)),
            isTrue, reason: 'mode: $mode');
      }
    });

    test('a missing udp_relay key counts as on, so no false warning', () {
      final parsed = CoreState.fromJson(<String, dynamic>{'console_on': true});
      expect(parsed.udpRelay, isTrue);
      expect(udpRelayWarningNeeded(parsed), isFalse);
    });
  });

  group('UDP mode copy', () {
    test('every mode has a title and a description in both languages', () {
      for (final words in [const Words(true), const Words(false)]) {
        for (final text in [
          words.consoleMasterTitle,
          words.consoleMasterOff,
          words.consoleMasterOn,
          words.udpSection,
          words.udpNormalTitle,
          words.udpNormalDetail,
          words.udpGameTitle,
          words.udpGameDetail,
          words.udpRelayOnlyTitle,
          words.udpRelayOnlyDetail,
          words.udpBatteryTitle,
          words.udpBatteryDetail,
          words.udpBatteryWarning,
          words.udpRelayWarning,
          words.udpRelayWarningAction,
          words.udpHelpTitle,
          words.udpHelpBrowseBody,
          words.udpHelpConsoleBody,
          words.udpHelpBlockedBody,
          words.udpHelpBatteryBody,
          words.udpHelpNetworkBody,
        ]) {
          expect(text.trim(), isNotEmpty);
        }
      }
    });

    test('the warning names UDP Relay in both languages', () {
      expect(const Words(true).udpRelayWarning, contains('UDP Relay'));
      expect(const Words(false).udpRelayWarning, contains('UDP Relay'));
    });

    test('the game mode description warns about higher WARP usage', () {
      expect(const Words(false).udpGameDetail, contains('WARP'));
      expect(const Words(true).udpGameDetail, contains('WARP'));
      expect(const Words(false).udpBatteryWarning.toLowerCase(), contains('battery'));
    });

    test('Persian and English copy are actually different', () {
      expect(const Words(true).udpNormalTitle, isNot(const Words(false).udpNormalTitle));
      expect(const Words(true).udpRelayWarning, isNot(const Words(false).udpRelayWarning));
    });
  });
}
