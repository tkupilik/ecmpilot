"""Local eCMPilot UDP v0 simulator. No CAN interface, vehicle access or external packages."""
import argparse
import json
import re
import socket
import time
import uuid
from pathlib import Path

ID = re.compile(r"[a-f0-9]{32}\Z")
TIME = re.compile(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]\Z")


class Gateway:
    def __init__(self, scenario="charging", clock=time.monotonic):
        self.clock = clock
        self.start = clock()
        self.boot = uuid.uuid4().hex
        self.id = "simulated-corsa"
        self.scenario = scenario
        self.seq = 0
        self.climate = scenario == "running"
        self.mode = "immediate"
        self.start_local = "23:00"
        self.revs = {"climate": 0, "charge": 0}
        self.commands = {}
        self.executions = 0

    def now(self):
        return int((self.clock() - self.start) * 1000)

    def advance(self):
        now = self.now()
        self.commands = {k: v for k, v in self.commands.items() if now - v["created"] < 600_000}
        for command in self.commands.values():
            if command["result"]["phase"] != "accepted" or now - command["created"] < 1100:
                continue
            result = command["result"]
            result["revision"] = 2
            if self.scenario == "unconfirmed":
                result.update(phase="unknown", error="unconfirmed")
                continue
            if command["rev"] != self.revs[command["domain"]]:
                result.update(phase="failed", error="superseded")
                continue
            args = command["args"]
            if command["domain"] == "climate":
                self.climate = args["enabled"]
            else:
                self.mode = args["mode"]
                self.start_local = args.get("start_local", self.start_local)
            self.executions += 1
            result.update(phase="succeeded", error=None)

    def info(self):
        operations = {} if self.scenario == "readonly" else {
            "climate.set": {"verified": True, "confirmation": True, "enabled": [True, False]},
            "charge.mode.set": {"verified": True, "confirmation": True, "modes": ["immediate", "deferred"], "persistent_schedule": True},
        }
        return {"firmware": "local simulator 0.1", "security": "lab-plain", "timezone": "Europe/Prague",
                "fields": ["soc_pct", "range_km", "cabin_c", "outside_c", "battery_min_c", "battery_max_c", "odometer_km"], "operations": operations}

    def state(self):
        age = 120_000 if self.scenario == "asleep" else 100
        values = {"soc_pct": 68.2, "range_km": 218, "cabin_c": 16, "outside_c": 9,
                  "battery_min_c": 24, "battery_max_c": 27, "odometer_km": 42810}
        if self.scenario == "limited":
            values.update(range_km=None, cabin_c=None, odometer_km=None)
        state = {k: {"value": v, "age_ms": age} for k, v in values.items()}
        state["vehicle"] = {"awake": self.scenario != "asleep", "parked": True, "age_ms": 100}
        state["climate"] = {"state": "on" if self.climate else "off", "rev": self.revs["climate"], "age_ms": age}
        state["charge"] = {"state": "charging" if self.mode == "immediate" else "waiting", "mode": self.mode,
                           "rev": self.revs["charge"], "connected": True, "power_kw": 7.2 if self.mode == "immediate" else 0,
                           "remaining_min": 135 if self.mode == "immediate" else None, "start_local": self.start_local, "age_ms": age}
        return state

    def handle(self, request):
        self.advance()
        self.seq += 1
        rid = request.get("id")
        if not isinstance(rid, str) or not ID.fullmatch(rid):
            raise ValueError("Missing/invalid request ID")
        response = {"v": 0, "type": "status", "gw": self.id, "boot": self.boot, "reply_to": rid, "seq": self.seq, "now_ms": self.now()}
        op = request.get("op")
        write = op in ("climate.set", "charge.mode.set")

        def reject(code, reason=None):
            if write:
                response["command"] = {"id": rid, "revision": 1, "phase": "rejected", "error": code}
                if reason:
                    response["command"]["reason"] = reason
            else:
                response["error"] = code
            return response

        if type(request.get("v")) is not int or request["v"] != 0 or request.get("type") != "request":
            return reject("unsupported_version")
        client = request.get("client")
        if not isinstance(client, str) or not ID.fullmatch(client):
            return reject("invalid_argument")
        if set(request) - {"v", "type", "gw", "boot", "client", "id", "deadline_ms", "domain_rev", "op", "args"}:
            return reject("unsupported_argument")
        if request.get("gw") not in (None, self.id) or write and request.get("gw") != self.id:
            return reject("wrong_gateway")
        args = request.get("args")
        if not isinstance(args, dict):
            return reject("invalid_argument")
        if op == "info.get" and not args:
            response["info"] = self.info()
        elif op == "state.get" and not args:
            response["state"] = self.state()
        elif op == "command.get" and set(args) == {"command_id"}:
            old = self.commands.get((client, args["command_id"]))
            if old:
                response["command"] = dict(old["result"])
            else:
                response["error"] = "result_unknown"
        elif write:
            fingerprint = json.dumps(request, sort_keys=True, separators=(",", ":"))
            old = self.commands.get((client, rid))
            if old:
                if old["fingerprint"] != fingerprint:
                    return reject("id_conflict")
                response["command"] = dict(old["result"])
                return response
            if request.get("boot") != self.boot:
                return reject("boot_changed")
            deadline = request.get("deadline_ms")
            if type(deadline) is not int or not 0 < deadline - self.now() <= 5000:
                return reject("expired")
            if op not in self.info()["operations"]:
                return reject("unsupported_operation")
            if op == "climate.set":
                if set(args) != {"enabled"} or type(args["enabled"]) is not bool:
                    return reject("unsupported_argument")
                domain = "climate"
            else:
                domain = "charge"
                if args.get("mode") == "immediate":
                    if set(args) != {"mode"}:
                        return reject("unsupported_argument")
                elif args.get("mode") == "deferred":
                    if set(args) != {"mode", "start_local", "timezone"} or not TIME.fullmatch(str(args.get("start_local"))) or args.get("timezone") != "Europe/Prague":
                        return reject("invalid_argument")
                else:
                    return reject("invalid_argument")
            if self.scenario == "asleep":
                return reject("vehicle_unavailable")
            if self.scenario == "reject":
                return reject("precondition_failed", "not_parked")
            if type(request.get("domain_rev")) is not int or request["domain_rev"] != self.revs[domain]:
                return reject("conflict")
            if len(self.commands) >= 256:
                return reject("busy")
            active = [v for v in self.commands.values() if v["domain"] == domain and v["result"]["phase"] == "accepted"]
            if active and not (op == "climate.set" and args["enabled"] is False):
                return reject("busy")
            for previous in active:
                previous["result"].update(phase="failed", error="superseded", revision=2)
            self.revs[domain] += 1
            result = {"id": rid, "revision": 1, "domain_rev": self.revs[domain], "phase": "accepted", "error": None}
            self.commands[(client, rid)] = {"fingerprint": fingerprint, "created": self.now(), "domain": domain, "rev": self.revs[domain], "args": args, "result": result}
            response["command"] = dict(result)
        else:
            return reject("unsupported_operation")
        return response


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=42840)
    parser.add_argument("--scenario", choices=["charging", "running", "asleep", "limited", "readonly", "reject", "unconfirmed"], default="charging")
    parser.add_argument("--drop-first-command-reply", action="store_true")
    parser.add_argument("--max-runtime", type=float, default=0, help="Optional automatic shutdown, seconds")
    parser.add_argument("--ready-file", type=Path)
    options = parser.parse_args()
    gateway = Gateway(options.scenario)
    dropped = False
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((options.bind, options.port)); sock.settimeout(0.2)
        host, port = sock.getsockname()
        print(f"eCMPilot simulator {host}:{port} | scenario={options.scenario} | no CAN access", flush=True)
        if options.ready_file:
            options.ready_file.parent.mkdir(parents=True, exist_ok=True)
            options.ready_file.write_text(json.dumps({"host": host, "port": port, "gw": gateway.id}), encoding="utf-8")
        try:
            while not options.max_runtime or gateway.clock() - gateway.start < options.max_runtime:
                gateway.advance()
                try:
                    raw, peer = sock.recvfrom(1025)
                except socket.timeout:
                    continue
                if len(raw) > 1024:
                    continue
                try:
                    body = json.loads(raw.decode("utf-8"), object_pairs_hook=strict_object, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Invalid JSON constant")))
                    if not isinstance(body, dict):
                        continue
                    response = gateway.handle(body)
                    payload = json.dumps(response, separators=(",", ":"), allow_nan=False).encode("utf-8")
                    if len(payload) > 1024:
                        raise ValueError("Response exceeds protocol size cap")
                    command = response.get("command")
                    if command:
                        print(f"{body['op']} {command['id'][:8]} -> {command['phase']} | executions={gateway.executions}", flush=True)
                    if options.drop_first_command_reply and not dropped and body.get("op") in ("climate.set", "charge.mode.set"):
                        dropped = True
                        print("Dropped first write reply after recording acceptance", flush=True)
                        continue
                    sock.sendto(payload, peer)
                except (ValueError, KeyError, TypeError, RecursionError) as error:
                    print(f"Ignored malformed request: {error}", flush=True)
        except KeyboardInterrupt:
            pass
    print(f"Simulator stopped; executed workflows={gateway.executions}", flush=True)


if __name__ == "__main__":
    main()
