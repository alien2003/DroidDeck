import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

from test_droiddeckctl import DUMPSYS_DISPLAY, FakeAdbTest, ctl, ended_state, scenario, state

SCENARIO = Path(__file__).resolve().parents[1] / "scenarios" / "pen-desktop.json"


def parse(*argv):
    return ctl.build_parser().parse_args(["input", "pen", *argv])


def full(*argv):
    return ctl.pen_samples(parse(*argv))


def phases(samples):
    return [s["phase"] for s in samples]


def dumpsys_input(rotation=0, size=(1140, 2616), active=1):
    """`dumpsys input` as the Find N6 prints it (twice over), with its inactive inner display listed first."""
    inner = ("Viewport INTERNAL: displayId=2, uniqueId=local:147, port=147, orientation=0, "
             "logicalFrame=[0, 0, 2248, 2480], physicalFrame=[0, 0, 2248, 2480], deviceSize=[2248, 2480], isActive=[0]")
    shown = (f"Viewport INTERNAL: displayId=0, uniqueId=local:148, port=148, orientation={rotation}, "
             f"logicalFrame=[0, 0, {size[0]}, {size[1]}], physicalFrame=[0, 0, {size[0]}, {size[1]}], "
             f"deviceSize=[{size[0]}, {size[1]}], isActive=[{active}]")
    reader = (f"Input Reader State (Nums of device: 11):\n  Device 6: touchpanel_pen\n      {inner}\n"
              f"  Configuration:\n      TouchPadRightClickState: 0\n      Viewports:\n        {inner}\n        {shown}\n"
              " OplusInputReader mMergeEnable =  0\n")
    return reader + "\nINPUT MANAGER (dumpsys input)\n\n" + reader


def frames(commands):
    """Every evdev frame a uinput script injects, as {code: value}."""
    found, frame = [], {}
    for command in commands:
        if command["command"] == "inject":
            events = command["events"]
            for code, value in zip(events[1::3], events[2::3]):
                if code == "SYN_REPORT":
                    found.append(frame)
                    frame = {}
                else:
                    frame[code] = value
    return found


class PenSamplesTest(unittest.TestCase):
    def test_a_tap_hovers_lands_lifts_and_leaves_at_the_rate(self):
        samples, fingers = full("tap", "0.5", "0.5", "--space", "fraction", "--hold", "20")
        self.assertEqual(["hover"] * 10 + ["down", "move", "up"] + ["hover"] * 10 + ["out"], phases(samples))
        times = [s["t"] for s in samples]
        self.assertEqual(sorted(times), times)
        self.assertEqual([0, 8, 80, 100, 180, 180], [times[0], times[1], times[10], times[12], times[-2], times[-1]])
        self.assertTrue(all("pressure" not in s for s in samples if s["phase"] in ("hover", "out")))
        self.assertEqual({0.5}, {s["pressure"] for s in samples if s["phase"] in ("down", "move", "up")})
        self.assertTrue(all((s["x"], s["y"], s["buttons"]) == (0.5, 0.5, []) for s in samples))
        self.assertEqual([], fingers)

    def test_without_hover_the_pen_lands_and_leaves_with_its_lift(self):
        samples, _ = full("tap", "10", "20", "--hover-ms", "0", "--hold", "0")
        self.assertEqual([("down", 0), ("up", 0), ("out", 0)], [(s["phase"], s["t"]) for s in samples])

    def test_a_stroke_interpolates_position_and_pressure_over_contact_and_tilt_over_the_gesture(self):
        samples, _ = full("stroke", "0.1", "0.2", "0.9", "0.8", "--space", "fraction", "--duration", "100", "--hover-ms", "20",
                          "--rate", "100", "--pressure", "0.2:0.8", "--tilt-x=-30:30", "--tilt-y", "10")
        self.assertEqual(["hover", "hover", "down"] + ["move"] * 9 + ["up", "hover", "hover", "out"], phases(samples))
        contact = [s for s in samples if s["phase"] in ("down", "move", "up")]
        self.assertEqual((20, 0.1, 0.2, 0.2), (contact[0]["t"], contact[0]["x"], contact[0]["y"], contact[0]["pressure"]))
        self.assertEqual((120, 0.9, 0.8, 0.8), (contact[-1]["t"], contact[-1]["x"], contact[-1]["y"], contact[-1]["pressure"]))
        pressures = [s["pressure"] for s in contact]
        self.assertEqual(sorted(pressures), pressures)
        self.assertAlmostEqual(0.5, contact[5]["x"])
        self.assertEqual((-30, 30), (samples[0]["tiltX"], samples[-1]["tiltX"]))
        self.assertEqual({10}, {s["tiltY"] for s in samples})
        self.assertEqual((0.9, 0.8), (samples[-1]["x"], samples[-1]["y"]))

    def test_hover_glides_to_the_target_and_keep_stays_near(self):
        samples, _ = full("hover", "100", "200", "--to", "300", "400", "--duration", "300", "--distance", "0.5")
        self.assertEqual(["hover"] * 37 + ["out"], phases(samples))
        self.assertEqual((100, 200), (samples[0]["x"], samples[0]["y"]))
        self.assertEqual((300, 400, 300), (samples[-1]["x"], samples[-1]["y"], samples[-1]["t"]))
        self.assertEqual({0.5}, {s["distance"] for s in samples})
        kept, _ = full("hover", "100", "200", "--keep", "--rate", "60")
        self.assertEqual(["hover"] * 19, phases(kept))

    def test_a_side_button_is_held_while_the_pen_hovers_in_place(self):
        samples, _ = full("button", "secondary", "0.5", "0.5", "--space", "fraction", "--hold", "40")
        held = [s["t"] for s in samples if s["buttons"] == ["secondary"]]
        self.assertEqual(80, held[0])
        self.assertLess(held[-1], 120)
        self.assertEqual([], samples[-2]["buttons"])
        self.assertEqual({"hover"}, set(phases(samples[:-1])))
        self.assertEqual("out", samples[-1]["phase"])

    def test_a_finger_lands_for_the_middle_half_of_the_gesture(self):
        _, fingers = full("hover", "0.5", "0.5", "--space", "fraction", "--duration", "400", "--finger", "0.1", "0.9")
        self.assertEqual([{"t": 100, "phase": "down", "id": 0, "x": 0.1, "y": 0.9}, {"t": 300, "phase": "up", "id": 0}], fingers)

    def test_the_request_carries_fields_over_instead_of_repeating_them(self):
        request = ctl.input_request(parse("button", "primary", "0.25", "0.75", "--space", "fraction", "--hold", "16",
                                          "--tilt-x", "20", "--tool", "eraser", "--batch", "2"))
        self.assertEqual({"type": "pen", "via": "pipeline", "space": "fraction", "tool": "eraser", "batch": 2},
                         {k: v for k, v in request.items() if k != "samples"})
        samples = request["samples"]
        self.assertEqual({"t": 0, "phase": "hover", "x": 0.25, "y": 0.75, "tiltX": 20}, samples[0])
        self.assertEqual({"t": 8, "phase": "hover"}, samples[1])
        changes = [(s["t"], s["buttons"]) for s in samples if "buttons" in s]
        self.assertEqual([(80, ["primary"]), (96, [])], changes)

    def test_rounding_keeps_times_whole_and_never_decreasing(self):
        samples, _ = full("stroke", "0", "0", "1919", "1079", "--rate", "144", "--duration", "997")
        times = [s["t"] for s in samples]
        self.assertTrue(all(isinstance(t, int) for t in times))
        self.assertEqual(sorted(times), times)


class PenValidationTest(unittest.TestCase):
    def refused(self, samples, **request):
        with self.assertRaises(ctl.ControlError) as caught:
            ctl.validate_pen(dict({"type": "pen", "space": "fraction", "samples": samples}, **request))
        self.assertEqual("INVALID_INPUT", caught.exception.code)
        return str(caught.exception)

    def test_phase_order_follows_a_real_pen(self):
        here = {"x": 0.5, "y": 0.5}
        cases = [
            ([{"t": 0, "phase": "move", **here}], "sample 0: move needs a down first"),
            ([{"t": 0, "phase": "hover", **here}, {"t": 5, "phase": "up"}], "sample 1: up needs a down first"),
            ([{"t": 0, "phase": "out", **here}], "out needs a hover first"),
            ([{"t": 0, "phase": "down", **here}, {"t": 5, "phase": "hover"}], "hover while the pen is down"),
            ([{"t": 0, "phase": "down", **here}, {"t": 5, "phase": "down"}], "down while the pen is already down"),
            ([{"t": 0, "phase": "down", **here}, {"t": 5, "phase": "move"}], "end with the pen down"),
            ([{"t": 0, "phase": "hover", **here}, {"t": 5, "phase": "cancel"}], "cancel needs a down first"),
            ([{"t": 10, "phase": "hover", **here}, {"t": 5, "phase": "out"}], "t 5 is before the previous 10"),
            ([{"t": 0, "phase": "hover", "x": 0.5}], "x and y are required on the first sample"),
            ([{"t": 0, "phase": "float", **here}], "phase 'float' is not one of"),
        ]
        for samples, message in cases:
            with self.subTest(message=message):
                self.assertIn(message, self.refused(samples))

    def test_a_pen_may_land_without_hovering_and_lift_straight_out(self):
        here = {"x": 0.5, "y": 0.5}
        ctl.validate_pen({"space": "fraction", "samples": [{"t": 0, "phase": "down", **here}, {"t": 5, "phase": "up"},
                                                           {"t": 5, "phase": "out"}, {"t": 9, "phase": "down"},
                                                           {"t": 12, "phase": "cancel"}]})

    def test_fingers_must_land_before_they_move_and_all_lift(self):
        pen = [{"t": 0, "phase": "hover", "x": 0.5, "y": 0.5}]
        self.assertIn("finger 0 is not down", self.refused(pen, fingers=[{"t": 0, "phase": "up", "id": 0}]))
        self.assertIn("still down at the end", self.refused(pen, fingers=[{"t": 0, "phase": "down", "id": 2, "x": 0.1, "y": 0.1}]))
        self.assertIn("id 10 must be 0..9", self.refused(pen, fingers=[{"t": 0, "phase": "down", "id": 10, "x": 0.1, "y": 0.1}]))


class PenCommandTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        resolve = unittest.mock.patch.object(ctl, "resolve_serial", wraps=ctl.resolve_serial)
        self.resolve = resolve.start()
        self.addCleanup(resolve.stop)

    def test_the_gesture_goes_to_the_input_provider_and_its_answer_comes_back(self):
        answer = {"ok": True, "command": "input", "type": "pen", "via": "pipeline", "samples": 24, "events": 31, "ms": 190,
                  "route": "tablet"}
        self.configure({"input": answer})
        code, out = self.run_ctl("input", "pen", "tap", "0.5", "0.5", "--space", "fraction", "--hold", "20",
                                 "--finger", "0.1", "0.9")
        self.assertEqual(0, code)
        self.assertEqual("tablet", out["route"])
        self.assertEqual("fake-1", out["deviceSerial"])
        request = self.calls("input")[0]["request"]
        self.assertEqual(ctl.input_request(parse("tap", "0.5", "0.5", "--space", "fraction", "--hold", "20",
                                                 "--finger", "0.1", "0.9")), request)
        self.assertEqual(24, len(request["samples"]))
        self.assertEqual(["down", "up"], [f["phase"] for f in request["fingers"]])

    def test_out_of_range_values_are_refused_before_adb(self):
        cases = [
            (("tap", "0.5", "0.5", "--space", "fraction", "--pressure", "1.5"), "pressure 1.5 must be 0..1"),
            (("tap", "0.5", "0.5", "--space", "fraction", "--tilt-x", "120"), "tiltX 120 must be -90..90"),
            (("hover", "1.2", "0.5", "--space", "fraction"), "x 1.2 must be 0..1"),
            (("hover", "-4", "10"), "x -4 must be 0 or more"),
            (("hover", "10", "10", "--distance", "-1"), "distance -1 must be 0 or more"),
            (("tap", "10", "10", "--finger", "-1", "5"), "finger 0: x -1 must be 0 or more"),
            (("tap", "10", "10", "--rate", "0"), "--rate must be 1..1000 Hz"),
            (("tap", "10", "10", "--batch", "9"), "--batch must be 1..8"),
            (("tap", "10", "10", "--hold", "-5"), "--hold must not be negative"),
            (("stroke", "0", "0", "9", "9", "--duration", "40000", "--rate", "50"), "lasts 40160 ms, more than 30000"),
            (("hover", "1", "1", "--duration", "5000", "--rate", "1000"), "more than 4096"),
        ]
        for argv, message in cases:
            with self.subTest(message=message):
                code, out = self.run_ctl("input", "pen", *argv)
                self.assertEqual(2, code)
                self.assertEqual("INVALID_INPUT", out["error"]["code"])
                self.assertIn(message, out["error"]["message"])
        self.assertFalse(self.resolve.called)
        self.assertFalse(self.calls())

    def test_a_gesture_too_long_for_an_adb_command_is_refused_before_adb(self):
        code, out = self.run_ctl("input", "pen", "stroke", "0", "0", "2000", "1000", "--duration", "9000")
        self.assertEqual(2, code)
        self.assertEqual("INVALID_INPUT", out["error"]["code"])
        self.assertEqual("The pen gesture makes a 68 KiB adb command, over the 60 KiB adb takes; shorten it or lower --rate",
                         out["error"]["message"])
        self.assertFalse(self.resolve.called)
        self.assertFalse(self.calls())
        self.configure({"input": {"ok": True, "command": "input", "type": "pen", "via": "pipeline", "route": "pointer"}})
        code, _ = self.run_ctl("input", "pen", "stroke", "0", "0", "2000", "1000", "--duration", "7500")
        self.assertEqual(0, code)
        args = self.calls("input")[0]["args"]
        self.assertLess(len(" ".join(args[args.index("content"):])), ctl.ADB_COMMAND_CAP)

    def test_a_gesture_over_30_s_is_refused_before_its_samples_are_made(self):
        cases = [("hover", "1", "1", "--duration", "1000000000"), ("tap", "1", "1", "--hold", "1000000000"),
                 ("tap", "1", "1", "--hover-ms", "15000"), ("stroke", "0", "0", "1", "1", "--duration", "1000000000"),
                 ("button", "primary", "1", "1", "--hold", "29841")]
        for argv in cases:
            with self.subTest(argv=argv), unittest.mock.patch.object(ctl, "pen_times", side_effect=AssertionError):
                code, out = self.run_ctl("input", "pen", *argv)
                self.assertEqual(2, code)
                self.assertEqual("INVALID_INPUT", out["error"]["code"])
                self.assertIn("more than 30000", out["error"]["message"])
        self.assertEqual(30_000, full("button", "primary", "1", "1", "--hold", "29840")[0][-1]["t"])
        self.assertEqual(30_000, full("tap", "1", "1", "--hover-ms", "14970")[0][-1]["t"])

    def test_a_session_without_a_screen_for_the_pen_exits_4(self):
        self.configure({"input": {"ok": False, "error": {"code": "NO_PEN_SINK", "message": "No session screen is open"}}})
        code, out = self.run_ctl("input", "pen", "hover", "10", "10")
        self.assertEqual(4, code)
        self.assertEqual("NO_PEN_SINK", out["error"]["code"])

    def test_the_session_error_codes_keep_their_exits(self):
        self.assertEqual(4, ctl.exit_code(ctl.ControlError("x", "SESSION_NOT_READY")))
        self.assertEqual(2, ctl.exit_code(ctl.ControlError("x", "INVALID_INPUT")))


class PenUinputTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        resolve = unittest.mock.patch.object(ctl, "resolve_serial", wraps=ctl.resolve_serial)
        self.resolve = resolve.start()
        self.addCleanup(resolve.stop)
        self.configure({}, dumpsys_input=dumpsys_input())

    def pen(self, *argv):
        return self.run_ctl("input", "pen", *argv, "--via", "uinput")

    def script(self):
        [call] = [c for c in self.calls() if c["args"][:3] == ["shell", "uinput", "-"]]
        return [json.loads(line) for line in call["stdin"].splitlines()]

    def test_a_stroke_registers_a_virtual_direct_pen_and_draws_through_uinput_without_the_app(self):
        code, out = self.pen("stroke", "0.25", "0.5", "0.75", "0.5", "--duration", "100", "--rate", "100", "--hover-ms", "20",
                             "--pressure", "0.2:1")
        self.assertEqual(0, code)
        self.assertEqual({"ok": True, "command": "input", "type": "pen", "via": "uinput", "samples": 16, "deviceSerial": "fake-1",
                          "viewport": {"display": 0, "rotation": 0, "size": [1140, 2616]}},
                         {k: v for k, v in out.items() if k != "ms"})
        self.assertFalse([c for c in self.calls() if "content" in c["args"]])
        commands = self.script()
        register = commands[0]
        self.assertEqual(("register", "virtual"), (register["command"], register["bus"]))
        self.assertEqual("UI_SET_EVBIT", register["configuration"][0]["type"])
        self.assertEqual([["type", "data"]] * 4, [list(c) for c in register["configuration"]])
        bits = {c["type"]: c["data"] for c in register["configuration"]}
        self.assertEqual(["EV_KEY", "EV_ABS"], bits["UI_SET_EVBIT"])
        self.assertEqual({"BTN_TOOL_PEN", "BTN_TOOL_RUBBER", "BTN_TOUCH", "BTN_STYLUS", "BTN_STYLUS2"}, set(bits["UI_SET_KEYBIT"]))
        self.assertEqual(["INPUT_PROP_DIRECT"], bits["UI_SET_PROPBIT"])
        ranges = {a["code"]: (a["info"]["minimum"], a["info"]["maximum"], a["info"]["resolution"]) for a in register["abs_info"]}
        self.assertEqual({"ABS_X": (0, 1140 * 16 - 1, 0), "ABS_Y": (0, 2616 * 16 - 1, 0), "ABS_PRESSURE": (0, 4095, 0),
                          "ABS_DISTANCE": (0, 255, 0), "ABS_TILT_X": (-90, 90, 0), "ABS_TILT_Y": (-90, 90, 0)}, ranges)
        self.assertEqual(set(bits["UI_SET_ABSBIT"]), set(ranges))
        self.assertEqual({"id": 1, "command": "delay", "duration": 1000}, commands[1])
        self.assertNotIn("updateTimeBase", [c["command"] for c in commands])
        self.assertEqual([{"id": 1, "command": "delay", "duration": 300}, {"id": 1, "command": "sync", "syncToken": ctl.UINPUT_SYNC}],
                         commands[-2:])
        self.assertEqual(140, sum(c["duration"] for c in commands[2:-2] if c["command"] == "delay"))
        drawn = frames(commands)
        self.assertEqual({"BTN_TOOL_PEN": 1, "ABS_X": 4560, "ABS_Y": 20928, "ABS_PRESSURE": 0, "ABS_DISTANCE": 0,
                          "ABS_TILT_X": 0, "ABS_TILT_Y": 0}, drawn[0])
        self.assertEqual({"ABS_X": 4561}, drawn[1])
        self.assertEqual({"BTN_TOUCH": 1, "ABS_X": 4560, "ABS_PRESSURE": 819}, drawn[2])
        self.assertEqual([{"ABS_X": 13680, "ABS_PRESSURE": 4095}, {"BTN_TOUCH": 0, "ABS_PRESSURE": 0}, {"ABS_X": 13681},
                          {"ABS_X": 13680}, {"BTN_TOOL_PEN": 0}], drawn[-5:])
        pressures = [f["ABS_PRESSURE"] for f in drawn[2:-4]]
        self.assertEqual(sorted(pressures), pressures)

    def test_display_fractions_and_tilt_reach_the_panel_in_its_natural_orientation(self):
        # A 1000 x 2000 portrait panel, a tap at (0.25, 0.75) of the screen as shown, tilted 30 to the right and 10 up
        cases = {0: ([1000, 2000], 4000, 24000, 30, -10), 1: ([2000, 1000], 3999, 8000, 10, 30),
                 2: ([1000, 2000], 11999, 7999, -30, 10), 3: ([2000, 1000], 12000, 23999, -10, -30)}
        for rotation, (size, x, y, tilt_x, tilt_y) in cases.items():
            with self.subTest(rotation=rotation):
                self.configure({}, dumpsys_input=dumpsys_input(rotation, size))
                self.log.unlink(missing_ok=True)
                code, out = self.pen("tap", "0.25", "0.75", "--tilt-x", "30", "--tilt-y=-10")
                self.assertEqual(0, code, out)
                self.assertEqual({"display": 0, "rotation": rotation, "size": size}, out["viewport"])
                commands = self.script()
                self.assertEqual([15999, 31999], [a["info"]["maximum"] for a in commands[0]["abs_info"][:2]])
                first = frames(commands)[0]
                self.assertEqual((x, y, tilt_x, tilt_y), (first["ABS_X"], first["ABS_Y"], first["ABS_TILT_X"], first["ABS_TILT_Y"]))

    def test_the_mapping_inverts_aosps_documented_rotation_example(self):
        # input_coordinates.md: raw (0, 2) of a 3 x 4 panel shows at (2, 2) of the 4 x 3 screen when rotated by 90
        self.assertEqual((0, 2, 0, 0), ctl.uinput_point(2 / 4, 2 / 3, 0, 0, 1, 3, 4))
        self.assertEqual((2, 3, 0, 0), ctl.uinput_point(1, 1, 0, 0, 0, 3, 4))
        self.assertEqual((0, 0, 0, 0), ctl.uinput_point(1, 1, 0, 0, 2, 3, 4))

    def test_the_eraser_and_side_buttons_use_the_stylus_keys(self):
        code, _ = self.pen("button", "secondary", "0.5", "0.5", "--hold", "40", "--tool", "eraser", "--distance", "12")
        self.assertEqual(0, code)
        drawn = frames(self.script())
        keys = [(code, value) for frame in drawn for code, value in frame.items() if code.startswith("BTN_")]
        self.assertEqual([("BTN_TOOL_RUBBER", 1), ("BTN_STYLUS2", 1), ("BTN_STYLUS2", 0), ("BTN_TOOL_RUBBER", 0)], keys)
        self.assertEqual(12, drawn[0]["ABS_DISTANCE"])

    def test_a_pen_held_still_keeps_reporting_at_the_rate(self):
        code, out = self.pen("hover", "1", "0.5", "--duration", "100", "--rate", "50")
        self.assertEqual(0, code)
        commands = self.script()
        drawn = frames(commands)
        self.assertEqual(out["samples"], len(drawn))
        self.assertEqual([18239, 18238, 18239, 18238, 18239, 18238], [f["ABS_X"] for f in drawn[:-1]])
        self.assertEqual([20, 20, 20, 20, 20, 300], [c["duration"] for c in commands[2:] if c["command"] == "delay"])

    def test_a_contact_without_pressure_still_touches(self):
        code, _ = self.pen("tap", "0.5", "0.5", "--pressure", "0", "--hover-ms", "0", "--hold", "0", "--distance", "5")
        self.assertEqual(0, code)
        drawn = frames(self.script())
        self.assertEqual({"BTN_TOOL_PEN", "BTN_TOUCH"}, {k for k, v in drawn[0].items() if k.startswith("BTN_") and v})
        self.assertEqual((1, 0), (drawn[0]["ABS_PRESSURE"], drawn[0]["ABS_DISTANCE"]))
        self.assertEqual([{"BTN_TOUCH": 0, "ABS_PRESSURE": 0, "ABS_DISTANCE": 5}, {"BTN_TOOL_PEN": 0}], drawn[1:])

    def test_a_shell_without_uinput_is_unavailable(self):
        for probe, message in (("no-uinput", "no `uinput` command"), ("no-dev-uinput", "cannot write /dev/uinput")):
            with self.subTest(probe=probe):
                self.configure({}, dumpsys_input=dumpsys_input(), uinput_probe=probe)
                code, out = self.pen("tap", "0.5", "0.5")
                self.assertEqual(3, code)
                self.assertEqual("UINPUT_UNAVAILABLE", out["error"]["code"])
                self.assertIn(message, out["error"]["message"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "uinput"]])

    def test_an_unknown_or_dark_display_is_refused_before_uinput(self):
        for dump, message in (("Input Reader State (Nums of device: 0):\n", "no internal display viewport"),
                              (dumpsys_input(active=0), "Display 0 is off")):
            with self.subTest(message=message):
                self.configure({}, dumpsys_input=dump)
                code, out = self.pen("tap", "0.5", "0.5")
                self.assertEqual((3, "NO_VIEWPORT"), (code, out["error"]["code"]))
                self.assertIn(message, out["error"]["message"])
        self.assertFalse([c for c in self.calls() if c["args"][:2] == ["shell", "uinput"]])

    def test_uinput_that_stops_early_is_a_failure(self):
        self.configure({}, dumpsys_input=dumpsys_input(), uinput_stdout="")
        code, out = self.pen("tap", "0.5", "0.5")
        self.assertEqual((3, "UINPUT_FAILED"), (code, out["error"]["code"]))
        self.assertIn("adb logcat -s UINPUT", out["error"]["message"])

    def test_what_uinput_cannot_do_is_refused_before_adb(self):
        cases = [
            (("tap", "0.5", "0.5", "--finger", "0.1", "0.1"), "--finger needs --via pipeline"),
            (("tap", "0.5", "0.5", "--batch", "2"), "--batch needs --via pipeline"),
            (("hover", "0.5", "0.5", "--keep"), "--keep needs --via pipeline"),
            (("tap", "0.5", "0.5", "--space", "output"), "--via uinput takes --space display, not 'output'"),
            (("tap", "0.5", "0.5", "--space", "fraction"), "--via uinput takes --space display, not 'fraction'"),
            (("tap", "1.5", "0.5"), "x 1.5 must be 0..1"),
            (("hover", "0.5", "0.5", "--distance", "0.5"), "distance 0.5 must be a whole number of device units, 0..255"),
            (("hover", "0.5", "0.5", "--distance", "256"), "distance 256 must be a whole number"),
        ]
        for argv, message in cases:
            with self.subTest(message=message):
                code, out = self.pen(*argv)
                self.assertEqual((2, "INVALID_INPUT"), (code, out["error"]["code"]))
                self.assertIn(message, out["error"]["message"])
        code, out = self.run_ctl("input", "pen", "tap", "0.5", "0.5", "--space", "display")
        self.assertEqual((2, "INVALID_INPUT"), (code, out["error"]["code"]))
        self.assertIn("--via pipeline takes --space output or fraction, not 'display'", out["error"]["message"])
        self.assertFalse(self.resolve.called)
        self.assertFalse(self.calls())

    def test_the_viewport_is_the_one_android_maps_internal_touchscreens_to(self):
        self.assertEqual({"display": 0, "rotation": 0, "size": [1140, 2616], "active": True}, ctl.input_viewport(dumpsys_input()))
        newest = ("      Viewports:\n        Viewport INTERNAL: displayId=3, uniqueId=local:1, port=<none>, orientation=3, "
                  "densityDpi=420 xDpi=409.432007, yDpi=411.891998, logicalFrame=[0, 0, 2400, 1080], "
                  "physicalFrame=[0, 0, 2400, 1080], deviceSize=[2400, 1080], isActive=[1]\n"
                  "        Viewport EXTERNAL: displayId=0, uniqueId=local:2, port=1, orientation=0, deviceSize=[1920, 1080], isActive=[1]\n")
        self.assertEqual({"display": 3, "rotation": 3, "size": [2400, 1080], "active": True}, ctl.input_viewport(newest))
        undated = "Viewport INTERNAL: displayId=0, orientation=2, logicalFrame=[0, 0, 720, 1280], deviceSize=[720, 1280]"
        self.assertEqual({"display": 0, "rotation": 2, "size": [720, 1280], "active": True}, ctl.input_viewport(undated))


def wev_line(obj, interface, event, args=""):
    """A line as wev prints it."""
    return "[%10u:%16s] %s%s\n" % (obj, interface, event, ": " + args if args else "")


def tool(obj, event, args=""):
    return wev_line(obj, "zwp_tablet_tool_v2", event, args)


WEV_TABLET = "".join([
    wev_line(3, "xdg_surface", "configure", "serial: 1"),
    wev_line(12, "zwp_tablet_seat_v2", "tablet_added", "13"),
    wev_line(13, "zwp_tablet_v2", "name", "DroidDeck Pen"),
    wev_line(12, "zwp_tablet_seat_v2", "tool_added", "14"),
    tool(14, "type", "320 (pen)"), tool(14, "capability", "1 (tilt)"), tool(14, "done"),
    tool(14, "proximity_in", "serial: 31; tablet: 13; surface: 3"), tool(14, "motion", "x, y: 320.000000, 240.000000"),
    tool(14, "down", "serial: 32"), tool(14, "pressure", "13107 (0.200)"), tool(14, "tilt", "x, y: 30.000000, -15.000000"),
    tool(14, "frame", "time: 1008"), tool(14, "pressure", "58981 (0.900)"), tool(14, "up"), tool(14, "proximity_out"),
    wev_line(12, "zwp_tablet_seat_v2", "tool_added", "15"), tool(15, "type", "321 (eraser)"),
    tool(15, "proximity_in", "serial: 40; tablet: 13; surface: 3"), tool(15, "down", "serial: 41"),
    tool(15, "pressure", "39321 (0.600)"), tool(15, "up"), tool(15, "proximity_out"),
    tool(14, "proximity_in", "serial: 50; tablet: 13; surface: 3"),
    tool(14, "button", "serial: 51;  button: 332 (stylus2), state: 1 (pressed)"),
    tool(14, "button", "serial: 52;  button: 332 (stylus2), state: 0 (released)"), tool(14, "proximity_out"),
])

# KWin announces a new tool to every client, so a type line alone does not mean the eraser came near wev.
WEV_POINTER = "".join([
    wev_line(3, "xdg_surface", "configure", "serial: 1"),
    wev_line(12, "zwp_tablet_seat_v2", "tool_added", "15"), tool(15, "type", "321 (eraser)"),
    wev_line(10, "wl_pointer", "enter", "serial: 5; surface: 3, x, y: 320.000000, 240.000000"),
    wev_line(10, "wl_pointer", "button", "serial: 6; time: 7; button: 273 (right), state: 1 (pressed)"),
])

XI2_DEVICES = ("WARNING: running xinput against an Xwayland server. See the xinput man page for details.\n"
               "⎡ Virtual core pointer                    \tid=2\t[master pointer  (3)]\n"
               "⎜   ↳ xwayland-pointer:10                     \tid=6\t[slave  pointer  (2)]\n"
               "⎜   ↳ xwayland-tablet stylus:10               \tid=10\t[slave  pointer  (2)]\n"
               "⎣ Virtual core keyboard                   \tid=3\t[master keyboard (2)]\n")


def xi2_event(number, name, source, detail=0, valuators=()):
    """An event as `xinput test-xi2` prints it, through the master pointer."""
    return (f"EVENT type {number} ({name})\n    device: 2 ({source})\n    time: 64624418\n    detail: {detail}\n"
            "    flags: \n    root: 400.00/318.00\n    event: 100.00/100.00\n    buttons:\n"
            "    modifiers: locked 0 latched 0 base 0 effective: 0\n    group: locked 0 latched 0 base 0 effective: 0\n"
            "    valuators:\n" + "".join(f"        {i}: {v:.2f}\n" for i, v in enumerate(valuators)) +
            "    windows: root 0x22e event 0x400001 child 0x0\n")


XI2_RAW = ("EVENT type 17 (RawMotion)\n    device: 2 (10)\n    time: 1\n    detail: 0\n    flags: \n    valuators:\n"
           "          2: 60000.00 (60000.00)\n"
           "EVENT type 15 (RawButtonPress)\n    device: 2 (10)\n    time: 2\n    detail: 1\n    flags: \n    valuators:\n")

# The eraser joined after xinput listed the devices, so only its classes tell that it is a tablet.
XI2_ERASER_CLASSES = ("EVENT type 1 (DeviceChanged)\n    device: 2 (11)\n    time: 642037409\n    reason: SlaveSwitch\n"
                      "\tReporting 1 classes:\n\t\tClass originated from: 11. Type: XIValuatorClass\n"
                      "\t\tDetail for Valuator 2:\n\t\t  Label: Abs Pressure\n\t\t  Range: 0.000000 - 65535.000000\n")

XI2_MOUSE_SCROLL = xi2_event(6, "Motion", 6, valuators=(10, 20, 15))

XI2_TABLET = XI2_DEVICES + "".join([
    XI2_MOUSE_SCROLL,
    xi2_event(6, "Motion", 10, valuators=(131072, 138936, 0, 30, -10, 0)),
    xi2_event(4, "ButtonPress", 10, detail=1),
    xi2_event(6, "Motion", 10, valuators=(131072, 138936, 32767, 30, -10, 0)),
    XI2_RAW, XI2_ERASER_CLASSES,
    xi2_event(4, "ButtonPress", 11, detail=1),
    xi2_event(4, "ButtonPress", 10, detail=3),
])

# What Xwayland 24.1.10 delivered before its fix: raw events only, and the mouse's scroll valuator is not pressure.
XI2_RAW_ONLY = XI2_DEVICES + XI2_RAW + XI2_MOUSE_SCROLL

XWAYLAND = "4242 /usr/bin/Xwayland :1 -auth /run/user/1000/xauth_abc -listenfd 51 -displayfd 49 -wm 53 -rootless"


def guest_answer(text=""):
    return {"ok": True, "exitCode": 0, "timedOut": False, "seconds": 0.1,
            "stdout": {"text": text, "truncated": False}, "stderr": {"text": "", "truncated": False}}


class PenDesktopScenarioTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        self.scenario = json.loads(SCENARIO.read_text())

    def resolved(self, **extra):
        return scenario.substitute(self.scenario, dict(self.scenario["vars"], **extra))

    def summaries(self):
        """Each in-guest summary program with the words the scenario asserts in its output."""
        steps, found = self.resolved()["steps"], []
        for index, step in enumerate(steps):
            if isinstance(step, list) and step[:1] == ["guest"] and "python3" in step:
                words = []
                for check in steps[index + 1:]:
                    if not (isinstance(check, dict) and check.get("assert") == "stdout.text"):
                        break
                    words.append(check["contains"])
                found.append((step[step.index("-c") + 1], words))
        return found

    def summarise(self, code, log):
        path = self.dir / "client.log"
        path.write_text(log)
        return subprocess.run([sys.executable, "-I", "-c", code, str(path)], capture_output=True, text=True,
                              check=True).stdout

    def test_every_command_parses_and_every_contact_starts_at_the_centre(self):
        resolved = self.resolved()
        self.assertNotIn("${", json.dumps(resolved))
        parser = ctl.build_parser()
        kinds = {"assert", "sleep", "screenshot", "record"}
        checks = {"equals", "in", "contains", "not", "exists"}
        presses = []
        for section in ("setup", "before", "steps", "after", "teardown"):
            for step in resolved[section]:
                with self.subTest(section=section, step=step):
                    if isinstance(step, dict):
                        self.assertEqual(1, len(kinds & set(step)))
                        self.assertTrue("assert" not in step or checks & set(step))
                        continue
                    try:
                        with contextlib.redirect_stderr(io.StringIO()):
                            args = parser.parse_args(["--serial", "fake-1", *step])
                    except SystemExit:
                        self.fail(f"droiddeckctl does not take {step}")
                    if args.command == "input":
                        request = ctl.input_request(args)
                        self.assertEqual(("pen", "pipeline", "fraction"), (request["type"], request["via"], request["space"]))
                        if args.pen_op == "stroke":
                            presses.append((args.x1, args.y1))
                        elif args.pen_op != "hover":
                            presses.append((args.x, args.y))
                    elif args.command == "guest":
                        self.assertEqual("--", args.argv[0])
                        self.assertTrue(args.argv[1:])
        self.assertEqual(20, len(presses))
        self.assertEqual({(0.5, 0.5)}, set(presses))

    def test_the_summaries_print_the_asserted_words_only_for_tablet_input(self):
        (wev, wayland), (xi2, x11) = self.summaries()
        self.assertEqual(["[wayland:proximity_in]", "[wayland:pressure]", "[wayland:eraser]", "[wayland:button=stylus2]"],
                         wayland)
        self.assertEqual(["[x11:motion]", "[x11:pressure]", "[x11:button_press]"], x11)

        counts, words = self.summarise(wev, WEV_TABLET).splitlines()
        self.assertEqual(wayland, words.split())
        self.assertEqual({"proximityIn": {"pen": 2, "eraser": 1}, "down": {"pen": 1, "eraser": 1},
                          "maxPressure": {"pen": 58981, "eraser": 39321}, "maxTilt": [30.0, 15.0],
                          "buttons": ["stylus2"], "pointerButtons": 0}, json.loads(counts))
        middle = [s["contains"] for s in self.resolved(sideButton="stylus")["steps"]
                  if isinstance(s, dict) and "button=" in s.get("contains", "")]
        self.assertEqual(["[wayland:button=stylus]"], middle)
        self.assertNotIn(middle[0], words)

        counts, words = self.summarise(xi2, XI2_TABLET).splitlines()
        self.assertEqual(x11, words.split())
        counts = json.loads(counts)
        self.assertEqual(({"stylus": 2}, 32767.0), (counts["motion"], counts["maxPressure"]))
        self.assertEqual({"stylus:1": 1, "11:1": 1, "stylus:3": 1}, counts["buttonPress"])

        for code, log in ((wev, WEV_POINTER), (xi2, XI2_RAW_ONLY)):
            with self.subTest(log=log[:40]):
                counts, words = self.summarise(code, log).splitlines()
                self.assertEqual("", words)
                json.loads(counts)

    def start_client(self, index, client, xwayland=XWAYLAND):
        """Run a client start step's shell script here, with a fake client, pgrep and a quick sleep."""
        starts = [step for step in self.resolved()["steps"]
                  if isinstance(step, list) and step[:1] == ["guest"] and "-c" in step and step[step.index("--") + 1] == "sh"]
        script = starts[index][starts[index].index("-c") + 1]
        fakes = self.dir / "fakes"
        fakes.mkdir()
        programs = {"pgrep": f"printf '%s\\n' '{xwayland}'", "sleep": f"exec {shutil.which('sleep')} 0.02", "client": client}
        for name, body in programs.items():
            (fakes / name).write_text("#!/bin/sh\n" + body + "\n")
            (fakes / name).chmod(0o755)
        runtime = tempfile.mkdtemp(prefix="dd-", dir="/tmp")
        self.addCleanup(shutil.rmtree, runtime)
        with socket.socket(socket.AF_UNIX) as display:
            display.bind(os.path.join(runtime, "wayland-1"))
        log = self.dir / "client.log"
        env = dict(os.environ, PATH=f"{fakes}:{os.environ['PATH']}", XDG_RUNTIME_DIR=runtime, WAYLAND_DISPLAY="wayland-1",
                   DISPLAY=":0")
        result = subprocess.run(["sh", "-c", script, "sh", str(fakes / "client"), str(log)], env=env, capture_output=True,
                                text=True, timeout=60)
        return result, log.read_text() if log.exists() else ""

    @unittest.skipUnless(shutil.which("setsid") and shutil.which("stdbuf"), "needs setsid and stdbuf")
    def test_the_clients_start_detached_on_kwins_displays(self):
        result, log = self.start_client(0, "echo '[         3:     xdg_surface] configure: serial: 1'")
        self.assertEqual((0, ""), (result.returncode, result.stderr))
        self.assertIn("xdg_surface", log)
        shutil.rmtree(self.dir / "fakes")
        result, log = self.start_client(1, 'echo "$* $DISPLAY $XAUTHORITY"; echo "⎡ Virtual core pointer"')
        self.assertEqual((0, ""), (result.returncode, result.stderr))
        self.assertIn("test-xi2 :1 /run/user/1000/xauth_abc", log)

    @unittest.skipUnless(shutil.which("setsid") and shutil.which("stdbuf"), "needs setsid and stdbuf")
    def test_a_client_that_does_not_start_fails_the_step_with_its_output(self):
        result, _ = self.start_client(1, "echo 'Unable to connect to X server' >&2")
        self.assertEqual(1, result.returncode)
        self.assertIn("xinput test-xi2 did not start:\nUnable to connect to X server", result.stderr)
        shutil.rmtree(self.dir / "fakes")
        result, _ = self.start_client(1, "true", xwayland="")
        self.assertEqual((1, "KWin runs no Xwayland\n"), (result.returncode, result.stderr))

    def run_on_fake_device(self, route, guest):
        ready = state()
        ready["session"]["mode"] = "lxqt"
        self.configure({"access": {"ok": True, "commands": True, "inbox": "/sdcard/agent-inbox"},
                        "override": {"ok": True}, "state": [ready, ready, ready, ended_state()],
                        "input": {"ok": True, "command": "input", "type": "pen", "via": "pipeline", "route": route},
                        "guest": guest, "stop": {"ok": True, "command": "stop"}}, dumpsys_display=DUMPSYS_DISPLAY)
        for name in ("wev", "xinput"):
            (self.dir / name).write_bytes(b"\x7fELF")
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()), \
                unittest.mock.patch.object(scenario.time, "sleep"):
            code = scenario.main([str(SCENARIO), "--adb", str(self.adb), "--serial", "fake-1", "--out", str(self.dir / "runs"),
                                  "--var", f"wev={self.dir / 'wev'}", "--var", f"xinput={self.dir / 'xinput'}"])
        return code, json.loads(out.getvalue())

    def test_a_run_draws_with_the_pen_and_checks_both_programs(self):
        (wev, _), (xi2, _) = self.summaries()
        code, report = self.run_on_fake_device("tablet", [
            guest_answer(), guest_answer(), guest_answer(self.summarise(wev, WEV_TABLET)),
            guest_answer(), guest_answer(), guest_answer(self.summarise(xi2, XI2_TABLET)), guest_answer()])
        self.assertEqual((0, 1), (code, report["passed"]), report)
        self.assertEqual([str(self.dir / "wev"), str(self.dir / "xinput")],
                         [c["args"][1] for c in self.calls() if c["args"][:1] == ["push"]])
        self.assertEqual(["wev", "xinput"], [c["request"]["name"] for c in self.calls("override")])
        pens = [c["request"] for c in self.calls("input")]
        self.assertEqual(22, len(pens))
        self.assertEqual({("pen", "pipeline", "fraction")}, {(p["type"], p["via"], p["space"]) for p in pens})
        self.assertEqual(["pen"] * 9 + ["eraser", "pen"], [p["tool"] for p in pens[:11]])
        guests = [c["request"] for c in self.calls("guest")]
        self.assertEqual({"WAYLAND_DISPLAY": "wayland-1"}, guests[0]["env"])
        self.assertEqual(["/opt/droiddeck-agent/bin/wev", "/tmp/droiddeck-pen-wev.log"], guests[0]["argv"][-2:])
        self.assertEqual(["/opt/droiddeck-agent/bin/xinput", "/tmp/droiddeck-pen-xi2.log"], guests[3]["argv"][-2:])
        self.assertEqual(["pkill", "-x", "wev|xinput"], guests[-1]["argv"])
        self.assertEqual(1, len(self.calls("stop")))
        folder = Path(report["runs"][0]["folder"])
        self.assertTrue((folder / "wayland.png").is_file() and (folder / "x11.png").is_file())

    def test_a_pen_that_reaches_the_desktop_as_a_mouse_fails_the_run_and_closes_the_programs(self):
        code, report = self.run_on_fake_device("pointer", [guest_answer()] * 2)
        self.assertEqual(1, code)
        self.assertIn("route is 'pointer', expected 'tablet'", report["runs"][0]["error"])
        self.assertEqual(2, len(self.calls("input")))
        self.assertEqual(["pkill", "-x", "wev|xinput"], self.calls("guest")[-1]["request"]["argv"])


if __name__ == "__main__":
    unittest.main()
