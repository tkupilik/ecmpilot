import copy
import json
import unittest
import uuid
from gateway_simulator import Gateway


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.time = 0.0
        self.gateway = Gateway(clock=lambda: self.time)
        self.client = uuid.uuid4().hex

    def request(self, op="climate.set", args=None, rev=0):
        return {"v": 0, "type": "request", "gw": self.gateway.id, "boot": self.gateway.boot,
                "client": self.client, "id": uuid.uuid4().hex, "deadline_ms": 5000, "domain_rev": rev,
                "op": op, "args": args if args is not None else {"enabled": True}}

    def test_retransmission_and_query_never_repeat_workflow(self):
        req = self.request()
        self.assertEqual("accepted", self.gateway.handle(req)["command"]["phase"])
        self.assertEqual("accepted", self.gateway.handle(req)["command"]["phase"])
        self.assertFalse(self.gateway.climate)
        self.time = 1.2
        self.assertEqual("succeeded", self.gateway.handle(req)["command"]["phase"])
        self.assertTrue(self.gateway.climate)
        self.assertEqual(1, self.gateway.executions)
        self.time = 8.0  # original admission deadline has expired, but its receipt remains queryable
        self.assertEqual("succeeded", self.gateway.handle(req)["command"]["phase"])
        self.assertEqual(1, self.gateway.executions)

    def test_same_id_different_content_is_rejected(self):
        req = self.request(); self.gateway.handle(req)
        changed = copy.deepcopy(req); changed["args"]["enabled"] = False
        self.assertEqual("id_conflict", self.gateway.handle(changed)["command"]["error"])

    def test_expired_never_seen_request_does_not_execute(self):
        req = self.request(); self.time = 6
        self.assertEqual("expired", self.gateway.handle(req)["command"]["error"])
        self.assertEqual(0, self.gateway.executions)

    def test_request_from_old_boot_does_not_execute(self):
        req = self.request(); req["boot"] = uuid.uuid4().hex
        self.assertEqual("boot_changed", self.gateway.handle(req)["command"]["error"])

    def test_delayed_start_after_newer_stop_is_rejected(self):
        delayed = self.request()
        stop = self.request(args={"enabled": False})
        self.gateway.handle(stop)
        self.assertEqual("conflict", self.gateway.handle(delayed)["command"]["error"])

    def test_stop_supersedes_pending_start(self):
        start = self.request(); self.gateway.handle(start)
        stop = self.request(args={"enabled": False}, rev=1); self.gateway.handle(stop)
        self.time = 1.2; self.gateway.advance()
        self.assertFalse(self.gateway.climate)
        self.assertEqual("failed", self.gateway.handle(start)["command"]["phase"])
        self.assertEqual(1, self.gateway.executions)

    def test_unknown_result_does_not_change_observation(self):
        self.gateway.scenario = "unconfirmed"
        req = self.request(); self.gateway.handle(req); self.time = 1.2
        self.assertEqual("unknown", self.gateway.handle(req)["command"]["phase"])
        self.assertFalse(self.gateway.climate)

    def test_capability_and_size_profiles(self):
        for scenario in ("charging", "running", "asleep", "limited", "readonly"):
            self.gateway.scenario = scenario
            for op in ("info.get", "state.get"):
                answer = self.gateway.handle(self.request(op, {}))
                self.assertLessEqual(len(json.dumps(answer, separators=(",", ":")).encode()), 1024)
        self.gateway.scenario = "readonly"
        self.assertEqual("unsupported_operation", self.gateway.handle(self.request())["command"]["error"])

    def test_query_is_scoped_to_original_client(self):
        original = self.request(); self.gateway.handle(original)
        query = self.request("command.get", {"command_id": original["id"]}); query["client"] = uuid.uuid4().hex
        self.assertEqual("result_unknown", self.gateway.handle(query)["error"])

    def test_unknown_write_argument_and_boolean_deadline_rejected(self):
        req = self.request(args={"enabled": True, "target_soc": 80})
        self.assertEqual("unsupported_argument", self.gateway.handle(req)["command"]["error"])
        req = self.request(); req["deadline_ms"] = True
        self.assertEqual("expired", self.gateway.handle(req)["command"]["error"])


if __name__ == "__main__":
    unittest.main()
