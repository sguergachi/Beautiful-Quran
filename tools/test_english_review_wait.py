#!/usr/bin/env python3
"""Real limit formats, shared cooldown/restart/cancellation, and MCP retry contracts."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.message import Message
import io
import json
from pathlib import Path
import tempfile
from threading import Barrier, Event
import unittest
from unittest.mock import patch
import urllib.error
from zoneinfo import ZoneInfo

import english_alignment_evidence as evidence
from english_review_wait import UsageWait, reset_deadline


class UsageWaitTest(unittest.TestCase):
    def setUp(self):
        self.zone = ZoneInfo('America/Toronto')
        self.now = datetime(2026, 10, 26, 20, 24, tzinfo=self.zone).timestamp()

    def test_daily_reset_and_next_day_rollover_with_safety_minute(self):
        with patch('english_review_wait.local_timezone', return_value=self.zone):
            self.assertEqual(datetime(2026, 10, 26, 23, 38, tzinfo=self.zone).timestamp(),
                             reset_deadline('ERROR: usage limit; try again at 11:37 PM.', self.now))
            self.assertEqual(datetime(2026, 10, 27, 18, 36, tzinfo=self.zone).timestamp(),
                             reset_deadline('try again at 6:35 PM.', self.now))

    def test_weekly_reset_includes_date_and_handles_compact_meridiem(self):
        with patch('english_review_wait.local_timezone', return_value=self.zone):
            for text in ['October 29, 2026 6:35 AM', 'Oct 29, 2026, 6:35AM']:
                self.assertEqual(datetime(2026, 10, 29, 6, 36, tzinfo=self.zone).timestamp(),
                                 reset_deadline('try again at ' + text, self.now))

    def test_relative_iso_and_utc_midnight(self):
        self.assertEqual(self.now + 180 + 60, reset_deadline('retry in 3 minutes', self.now))
        self.assertEqual(datetime(2026, 10, 28, 1, 1, tzinfo=timezone.utc).timestamp(),
                         reset_deadline('reset 2026-10-28T01:00:00Z', self.now))
        self.assertEqual(datetime(2026, 10, 28, 0, 1, tzinfo=timezone.utc).timestamp(),
                         reset_deadline('Resets at midnight UTC.', self.now))

    def test_unknown_format_uses_conservative_backoff(self):
        self.assertEqual(self.now + 3600, reset_deadline('usage limit', self.now))
        self.assertEqual(self.now + 3600, reset_deadline('reset 2026-99-99T33:40:00Z', self.now))

    def test_persisted_wait_survives_restart_and_does_not_probe_before_deadline(self):
        clock = [self.now]; waits = []
        def sleep(seconds): waits.append(seconds); clock[0] += seconds
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'usage.json'
            UsageWait(path, Event(), clock=lambda: clock[0]).defer('retry in 2 minutes')
            restored = UsageWait(path, Event(), clock=lambda: clock[0], sleeper=sleep)
            restored.wait()
            self.assertEqual([60, 60, 60], waits)
            self.assertEqual('ready', json.loads(path.read_text())['status'])

    def test_simultaneous_rejections_share_backoff_and_later_unknown_limits_back_off_more(self):
        clock = [self.now]
        with tempfile.TemporaryDirectory() as temporary:
            gate = UsageWait(Path(temporary) / 'usage.json', Event(), clock=lambda: clock[0])
            with ThreadPoolExecutor(max_workers=3) as pool:
                list(pool.map(gate.defer, ['usage limit'] * 3))
            self.assertEqual(1, gate.state['consecutive_limits'])
            self.assertEqual(self.now + 3600, gate.state['until'])
            clock[0] += 3601
            gate.defer('usage limit')
            self.assertEqual(clock[0] + 7200, gate.state['until'])

    def test_cancellation_interrupts_wait_and_preserves_restart_deadline(self):
        stop = Event()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'usage.json'
            gate = UsageWait(path, stop, clock=lambda: self.now, sleeper=lambda _: stop.set())
            gate.defer('retry in 2 hours')
            with self.assertRaises(KeyboardInterrupt): gate.wait()
            self.assertEqual('waiting', json.loads(path.read_text())['status'])

    def test_every_worker_waits_for_the_shared_deadline(self):
        clock = [self.now]; entered = Barrier(4); release = Event()
        def sleep(seconds):
            entered.wait(timeout=2)
            release.wait(timeout=2)
        with tempfile.TemporaryDirectory() as temporary:
            gate = UsageWait(Path(temporary) / 'usage.json', Event(), clock=lambda: clock[0], sleeper=sleep)
            gate.defer('retry in 2 hours')
            with ThreadPoolExecutor(max_workers=3) as pool:
                futures = [pool.submit(gate.wait) for _ in range(3)]
                try:
                    entered.wait(timeout=2)
                    self.assertTrue(all(not f.done() for f in futures))
                    clock[0] = gate.state['until']
                finally:
                    release.set()
                self.assertEqual([None] * 3, [f.result(timeout=2) for f in futures])


class QuranRetryTest(unittest.TestCase):
    def client(self):
        client = evidence.QuranEvidence.__new__(evidence.QuranEvidence)
        client.endpoint = 'https://mcp.quran.ai'
        client.headers = {'Accept': 'application/json'}
        client.last_call = 0
        client.cancelled = Event()
        return client

    def test_http_retry_after_is_obeyed_with_same_rpc_id_and_client(self):
        client = self.client(); requests = []; delays = []
        headers = Message(); headers['Retry-After'] = '42'
        def open_request(request, timeout):
            requests.append(json.loads(request.data))
            if len(requests) == 1:
                raise urllib.error.HTTPError(client.endpoint, 429, 'rate limit', headers, io.BytesIO())
            class Response(io.BytesIO):
                headers = {}
            return Response(json.dumps({'jsonrpc': '2.0', 'id': requests[0]['id'], 'result': {'ok': True}}).encode())
        with patch.object(evidence.urllib.request, 'urlopen', side_effect=open_request), patch.object(client, 'pause', side_effect=delays.append):
            self.assertEqual({'ok': True}, client.rpc('tools/call', {'name': 'fixture'}))
        self.assertEqual([42], delays)
        self.assertEqual(requests[0], requests[1])

    def test_tool_retry_message_is_obeyed_and_other_errors_are_not_retried(self):
        client = self.client(); delays = []
        limited = {'isError': True, 'content': [{'text': '[Rate Limited] fixture: Rate limited — retry in 19 seconds.'}]}
        success = {'structuredContent': {'ok': True}}
        with patch.object(client, 'rpc', side_effect=[limited, success]), patch.object(client, 'pause', side_effect=delays.append):
            self.assertEqual(success, client.call('fixture', {}))
        self.assertIn(19, delays)
        with patch.object(client, 'rpc', return_value={'isError': True, 'content': [{'text': 'private source sentinel'}]}) as rpc:
            with self.assertRaisesRegex(ValueError, 'no source payload saved') as error: client.call('fixture', {})
            self.assertNotIn('private source sentinel', str(error.exception))
            self.assertEqual(1, rpc.call_count)

    def test_retry_after_http_date_and_unknown_guidance(self):
        with patch.object(evidence.time, 'time', return_value=0):
            self.assertEqual(120, evidence.retry_delay('', 'Thu, 01 Jan 1970 00:02:00 GMT'))
        self.assertEqual(60, evidence.retry_delay('unknown retry message'))
        self.assertEqual(3600, evidence.retry_delay('daily limit reached'))


if __name__ == '__main__':
    unittest.main()
