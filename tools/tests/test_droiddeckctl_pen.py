import unittest
import unittest.mock

from test_droiddeckctl import FakeAdbTest, ctl


def parse(*argv):
    return ctl.build_parser().parse_args(["input", "pen", *argv])


def full(*argv):
    return ctl.pen_samples(parse(*argv))


def phases(samples):
    return [s["phase"] for s in samples]


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


if __name__ == "__main__":
    unittest.main()
