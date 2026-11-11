import unittest
from contextlib import redirect_stderr
from io import StringIO
from neo2_vision.flight_demo import can_retry_landing, identify_device, run_demo


class Clock:
    def __init__(self): self.t = 0.0
    def now(self): return self.t
    def sleep(self, duration): self.t += duration


class Runtime:
    def __init__(self):
        self.actions = []
        self.state = 'ready'
        self.airborne = False
        self.closed = False
        self.failed_takeoff = False
    def handle(self, action, params=None):
        self.actions.append(action)
        if action == 'prepare': return {'confirmation_token': 'token'}
        if action == 'arm': self.state = 'armed'; return {'lease': 'lease'}
        if action == 'takeoff':
            self.airborne = True; self.state = 'flying'
            if self.failed_takeoff: raise RuntimeError('lost response')
        if action == 'land': self.state = 'landing'
        if action == 'confirm_ground' and self.state == 'landing':
            self.airborne = False; self.state = 'ready'
        return {}
    def status(self):
        flying = self.state == 'flying'
        return {'connected': True, 'state': self.state, 'possible_airborne': self.airborne, 'connection': {'video': {'live': True}},
                'telemetry': {'control_ready': True, 'flying': flying, 'motors_on': flying, 'battery_percent': 50, 'altitude_m': 0}, 'limits': {'minimum_battery_percent': 20}}
    def close(self): self.closed = True


class DemoTests(unittest.TestCase):
    def test_no_video_reports_failed_control_setup_without_arming(self):
        class SetupFailed(Runtime):
            def status(self):
                return {
                    **super().status(),
                    'connection': {'phase': 'session_open', 'control_setup': {'state': 'failed'}},
                    'telemetry': {'control_ready': False},
                }

        runtime, clock = SetupFailed(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'Wi-Fi connected, but flight control setup failed'):
            run_demo(runtime, video_enabled=False, clock=clock.now, sleep=clock.sleep,
                     read_input=lambda _: self.fail('must not ask to arm'))
        self.assertEqual(runtime.actions, ['open'])
        self.assertTrue(runtime.closed)

    def test_normal_cycle_waits_then_requests_land_once(self):
        runtime, clock = Runtime(), Clock()
        responses = iter(['READY', 'GROUNDED'])
        result = run_demo(runtime, hover_seconds=3, read_input=lambda _: next(responses),
                          clock=clock.now, sleep=clock.sleep)
        self.assertFalse(result['possible_airborne'])
        self.assertGreaterEqual(clock.t, 3)
        self.assertEqual(runtime.actions.count('takeoff'), 1)
        self.assertEqual(runtime.actions.count('land'), 1)
        self.assertTrue(runtime.closed)

    def test_lost_takeoff_reply_never_retries_and_requests_land(self):
        runtime, clock = Runtime(), Clock()
        runtime.failed_takeoff = True
        with self.assertRaisesRegex(RuntimeError, 'lost response'):
            run_demo(runtime, read_input=lambda _: 'READY', clock=clock.now, sleep=clock.sleep)
        self.assertEqual(runtime.actions.count('takeoff'), 1)
        self.assertEqual(runtime.actions.count('land'), 1)
        self.assertTrue(runtime.closed)

    def test_without_operator_confirmation_never_arms(self):
        runtime, clock = Runtime(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
            run_demo(runtime, read_input=lambda _: '', clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('arm', runtime.actions)
        self.assertNotIn('takeoff', runtime.actions)
        self.assertTrue(runtime.closed)

    def test_resolved_prior_incident_resets_before_arming(self):
        class RecoveredRuntime(Runtime):
            adopted = True
            def __init__(self):
                super().__init__()
                self.state = 'fault'
                self.airborne = True
                self.recovery_required = True
            def handle(self, action, params=None):
                if action == 'confirm_ground' and self.state == 'fault':
                    self.actions.append(action)
                    self.airborne = False
                    self.recovery_required = False
                    return {}
                if action == 'reset':
                    self.actions.append(action)
                    self.state = 'ready'
                    return {}
                return super().handle(action, params)
            def status(self):
                return {**super().status(), 'fault_reason': 'unresolved_previous_flight' if self.state == 'fault' else None,
                        'recovery': {'required': self.recovery_required}}
        runtime, clock = RecoveredRuntime(), Clock()
        answers = iter(['READY', 'GROUNDED'])
        run_demo(runtime, read_input=lambda _: next(answers), clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('open', runtime.actions)
        self.assertLess(runtime.actions.index('confirm_ground'), runtime.actions.index('reset'))
        self.assertLess(runtime.actions.index('reset'), runtime.actions.index('arm'))

    def test_unrelated_fault_is_not_reset_or_armed(self):
        class FaultRuntime(Runtime):
            def status(self):
                return {**super().status(), 'state': 'fault', 'fault_reason': 'battery_stale',
                        'recovery': {'required': False}, 'possible_airborne': False}
        runtime, clock = FaultRuntime(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'fault remains unresolved'):
            run_demo(runtime, read_input=lambda _: 'READY', clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('reset', runtime.actions)
        self.assertNotIn('arm', runtime.actions)

    def test_low_battery_fails_before_operator_prompt(self):
        class LowBattery(Runtime):
            def status(self):
                status = super().status()
                status['telemetry']['battery_percent'] = 11
                return status
        runtime, clock = LowBattery(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'battery is 11%; at least 20%'):
            run_demo(runtime, read_input=lambda _: self.fail('must not ask to arm'), clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('arm', runtime.actions)
        self.assertTrue(runtime.closed)

    def test_final_landing_confirmation_requires_explicit_operator_action(self):
        class LandingRuntime(Runtime):
            def __init__(self):
                super().__init__()
                self.confirmed = False
            def status(self):
                status = super().status()
                if self.state == 'landing' and not self.confirmed:
                    status['telemetry'].update(flying=True, motors_on=True)
                    status['landing_confirmation'] = {'available': True}
                return status
            def handle(self, action, params=None):
                if action == 'confirm_landing':
                    self.actions.append(action)
                    self.confirmed = True
                    return {}
                return super().handle(action, params)
        runtime, clock = LandingRuntime(), Clock()
        answers = iter(['READY', 'LAND', 'GROUNDED'])
        run_demo(runtime, read_input=lambda _: next(answers), clock=clock.now, sleep=clock.sleep)
        self.assertEqual(runtime.actions.count('land'), 1)
        self.assertEqual(runtime.actions.count('confirm_landing'), 1)

    def test_landing_confirmation_is_not_sent_if_no_longer_available(self):
        class StaleLanding(Runtime):
            def __init__(self):
                super().__init__()
                self.reads = 0
            def status(self):
                status = super().status()
                if self.state == 'landing':
                    self.reads += 1
                    if self.reads == 1:
                        status['telemetry'].update(flying=True, motors_on=True)
                        status['landing_confirmation'] = {'available': True}
                    else:
                        status['landing_confirmation'] = {'available': False}
                return status
        runtime, clock = StaleLanding(), Clock()
        answers = iter(['READY', 'LAND', 'GROUNDED'])
        run_demo(runtime, read_input=lambda _: next(answers), clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('confirm_landing', runtime.actions)

    def test_waits_for_initial_battery_telemetry(self):
        class DelayedBattery(Runtime):
            def __init__(self):
                super().__init__()
                self.reads = 0
            def status(self):
                status = super().status()
                self.reads += 1
                if self.reads < 4:
                    status['telemetry']['battery_percent'] = None
                return status
        runtime, clock = DelayedBattery(), Clock()
        answers = iter(['READY', 'GROUNDED'])
        run_demo(runtime, read_input=lambda _: next(answers), clock=clock.now, sleep=clock.sleep)
        self.assertGreaterEqual(runtime.reads, 4)
        self.assertEqual(runtime.actions.count('takeoff'), 1)

    def test_missing_battery_never_arms(self):
        class MissingBattery(Runtime):
            def status(self):
                status = super().status()
                status['telemetry']['battery_percent'] = None
                return status
        runtime, clock = MissingBattery(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'battery level unavailable'):
            run_demo(runtime, read_input=lambda _: self.fail('must not prompt'), clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('arm', runtime.actions)

    def test_repeats_acknowledged_landing_at_most_twice(self):
        class RetryRuntime(Runtime):
            def __init__(self):
                super().__init__()
                self.lands = 0
            def handle(self, action, params=None):
                if action == 'land': self.lands += 1
                return super().handle(action, params)
            def status(self):
                status = super().status()
                if self.state == 'landing' and self.lands < 3:
                    status['telemetry'].update(flying=True, motors_on=True,
                        component_age_seconds={'motion': .1, 'control': .1})
                    status.update(land_acknowledged=True, pending_command_sequence=None)
                    status['limits']['telemetry_age'] = .5
                return status
        runtime, clock = RetryRuntime(), Clock()
        answers = iter(['READY', 'GROUNDED'])
        run_demo(runtime, read_input=lambda _: next(answers), clock=clock.now, sleep=clock.sleep)
        self.assertEqual(runtime.actions.count('land'), 3)
        self.assertGreaterEqual(clock.t, 13)

    def test_landing_fault_prompts_immediate_takeover_while_monitoring(self):
        class LandingFault(Runtime):
            def status(self):
                status = super().status()
                if self.state == 'landing' and clock.t < 6:
                    status.update(state='fault', fault_reason='landing_unconfirmed')
                    status['telemetry'].update(flying=True, motors_on=True)
                return status

        runtime, clock = LandingFault(), Clock()
        warnings = StringIO()

        def operator_response(prompt):
            if 'GROUNDED' in prompt:
                self.assertEqual(warnings.getvalue().count('Take over'), 1)
                return 'GROUNDED'
            return 'READY'

        with redirect_stderr(warnings):
            run_demo(runtime, read_input=operator_response, clock=clock.now, sleep=clock.sleep)
        self.assertEqual(runtime.actions.count('land'), 1)

    def test_no_landing_retry_without_fresh_acknowledged_airborne_evidence(self):
        status = {'connected': True, 'possible_airborne': True, 'state': 'landing',
                  'land_acknowledged': True, 'pending_command_sequence': None,
                  'limits': {'telemetry_age': .5}, 'landing_confirmation': {'available': False},
                  'telemetry': {'flying': True, 'motors_on': True, 'control_ready': True,
                                'component_age_seconds': {'motion': .1, 'control': .1}}}
        self.assertTrue(can_retry_landing(status))
        for field, value in [('land_acknowledged', False), ('connected', False),
                             ('pending_command_sequence', 1), ('possible_airborne', False)]:
            modified = {**status, field: value}
            self.assertFalse(can_retry_landing(modified), field)
        self.assertFalse(can_retry_landing({**status, 'landing_confirmation': {'available': True}}))
        sample = {**status['telemetry'], 'component_age_seconds': {'motion': .6, 'control': .1}}
        self.assertFalse(can_retry_landing({**status, 'telemetry': sample}))
        self.assertFalse(can_retry_landing({**status, 'telemetry': {**status['telemetry'], 'flying': False}}))

    def test_no_video_flight_still_requires_operator_and_lands(self):
        class NoVideo(Runtime):
            def status(self):
                return {**super().status(), 'connection': {'video': {'live': False}}}
        runtime, clock = NoVideo(), Clock()
        answers = iter(['READY', 'GROUNDED'])
        result = run_demo(runtime, video_enabled=False, read_input=lambda _: next(answers),
                          clock=clock.now, sleep=clock.sleep)
        self.assertFalse(result['possible_airborne'])
        self.assertEqual(runtime.actions.count('arm'), 1)
        self.assertEqual(runtime.actions.count('takeoff'), 1)
        self.assertEqual(runtime.actions.count('land'), 1)
        self.assertTrue(runtime.closed)

    def test_no_video_does_not_bypass_operator_confirmation(self):
        runtime, clock = Runtime(), Clock()
        with self.assertRaisesRegex(RuntimeError, 'not confirmed'):
            run_demo(runtime, video_enabled=False, read_input=lambda _: '', clock=clock.now, sleep=clock.sleep)
        self.assertNotIn('arm', runtime.actions)
        self.assertNotIn('takeoff', runtime.actions)

    def test_discovery_rejects_ambiguous_aircraft(self):
        candidate = {'candidate': True, 'name': 'DJI-NEO2-1234', 'address': 'id'}
        self.assertEqual(identify_device([candidate]), 'id')
        with self.assertRaises(RuntimeError): identify_device([candidate, candidate])
