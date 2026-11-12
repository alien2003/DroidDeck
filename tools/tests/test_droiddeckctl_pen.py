import json
import unittest
import unittest.mock

from test_droiddeckctl import FakeAdbTest, ctl


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


if __name__ == "__main__":
    unittest.main()
