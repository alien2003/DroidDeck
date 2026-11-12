import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
WAYLANDCOMP = REPO / 'app/src/main/cpp/waylandcomp'


def pkg_config(flag):
    tool = shutil.which('pkg-config')
    if tool is None:
        return None
    result = subprocess.run([tool, flag, 'wayland-server', 'wayland-client'], capture_output=True, text=True)
    return result.stdout.split() if result.returncode == 0 else None


class TabletV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        cflags, libs = pkg_config('--cflags'), pkg_config('--libs')
        if compiler is None or cflags is None or libs is None:
            raise unittest.SkipTest('A host C compiler, pkg-config and libwayland-dev are required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-tablet-v2-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = Path(cls.temp.name) / 'tablet-v2-test'
        build = subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                '-Wno-missing-field-initializers', '-I', str(WAYLANDCOMP / 'generated'),
                                '-I', str(WAYLANDCOMP / 'src'), *cflags, str(WAYLANDCOMP / 'src/wl_tablet.c'),
                                str(WAYLANDCOMP / 'generated/tablet-v2-protocol.c'),
                                str(REPO / 'tools/tests/tablet_v2_harness.c'), *libs, '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode != 0:
            raise AssertionError(build.stdout + build.stderr)

    def check_case(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual('PASS', result.stdout.strip().splitlines()[-1])

    def test_a_seat_bound_before_the_tablet_exists_gets_it_when_capabilities_arrive_and_later_seats_at_once(self):
        self.check_case('bind')

    def test_the_tablet_is_named_droiddeck_pen_with_a_virtual_bus_only_for_version_2_and_no_id_or_path(self):
        self.check_case('description')

    def test_tools_are_announced_lazily_with_pressure_tilt_and_distance_only_when_capable_and_never_rotation(self):
        self.check_case('tools')

    def test_hover_enters_with_motion_pressure_and_tilt_then_sends_only_changed_axes_and_no_frame_without_them(self):
        self.check_case('hover')

    def test_a_repeated_sample_sends_no_empty_frame_and_every_proximity_in_frame_carries_the_full_state(self):
        self.check_case('repeat')

    def test_tip_down_carries_pressure_and_tilt_and_lifts_with_up(self):
        self.check_case('down')

    def test_logical_right_is_btn_stylus2_and_logical_middle_is_btn_stylus(self):
        self.check_case('buttons')

    def test_motion_outside_the_surface_while_down_stays_on_that_surface_unclipped(self):
        self.check_case('grab')

    def test_moving_to_another_surface_or_client_leaves_the_old_one_before_entering_the_new_one(self):
        self.check_case('focus')

    def test_the_eraser_is_its_own_tool_and_switching_leaves_the_pen_first(self):
        self.check_case('eraser')

    def test_a_tool_switch_in_proximity_releases_the_old_tools_buttons_and_enters_the_new_tool_with_its_own_serial(self):
        self.check_case('switch')

    def test_set_cursor_needs_the_current_proximity_serial_of_the_focused_client_and_accepts_null(self):
        self.check_case('cursor')

    def test_a_surface_destroyed_while_down_gets_up_button_releases_proximity_out_and_a_frame(self):
        self.check_case('surface')

    def test_a_client_disconnecting_mid_stroke_leaves_nothing_behind(self):
        self.check_case('disconnect')

    def test_a_seat_bound_after_the_tools_exist_gets_the_tablet_then_the_tools(self):
        self.check_case('late')

    def test_each_binding_enters_with_its_own_tablet_and_destroyed_seats_or_tablets_stop_cleanly(self):
        self.check_case('bindings')

    def test_a_tool_destroyed_in_proximity_enters_again_after_the_client_rebinds(self):
        self.check_case('toolgone')

    def test_a_seat_bound_in_proximity_gets_nothing_until_its_tool_enters_with_the_full_state(self):
        self.check_case('rebind')


class TabletV2SourcesTest(unittest.TestCase):
    def test_the_protocol_xml_is_the_stable_tablet_v2(self):
        xml = (WAYLANDCOMP / 'protocols/tablet-v2.xml').read_text()
        self.assertIn('<protocol name="tablet_v2">', xml)
        self.assertIn('<interface name="zwp_tablet_manager_v2" version="2">', xml)

    def test_generated_files_look_like_their_siblings(self):
        for name in ('tablet-v2-protocol.c', 'tablet-v2-server-protocol.h', 'tablet-v2-client-protocol.h'):
            text = (WAYLANDCOMP / 'generated' / name).read_text()
            self.assertNotIn('Generated by wayland-scanner', text)
            self.assertNotIn('/**', text)
            self.assertIn('Copyright 2015-2024 © Red Hat, Inc.', text)
        self.assertIn('generated/tablet-v2-protocol.c', (WAYLANDCOMP / 'CMakeLists.txt').read_text())
        self.assertIn('src/wl_tablet.c', (WAYLANDCOMP / 'CMakeLists.txt').read_text())

    def test_the_tablet_file_includes_only_the_extension_header_and_its_protocol(self):
        includes = re.findall(r'^#include "([^"]+)"', (WAYLANDCOMP / 'src/wl_tablet.c').read_text(), re.MULTILINE)
        self.assertEqual(includes, ['droiddeck_ext.h', 'tablet-v2-server-protocol.h'])


if __name__ == '__main__':
    unittest.main()
