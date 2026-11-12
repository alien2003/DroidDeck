import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / 'app/src/main/cpp/waylandcomp/src/compositor.c'
EXTENSION = REPO / 'app/src/main/cpp/waylandcomp/src/droiddeck_ext.h'


def function(source, signature):
    start = source.index(signature)
    line = source[start:source.index('\n', start) + 1]
    if line.rstrip().endswith('}'):
        return line
    return source[start:source.index('\n}\n', start) + 3]


class PenEmulationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required')
        source = SOURCE.read_text()
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-pen-emulation-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        start = source.index('struct input_msg {')
        (folder / 'input_msg.inc').write_text(source[start:source.index('\n', start) + 1])
        start = source.index('#define PEN_IN ')
        end = source.index('static atomic_int g_pen_caps;\n', start)
        (folder / 'pen_state.inc').write_text(source[start:source.index('\n', end) + 1])
        extension = EXTENSION.read_text()
        start = extension.index('enum { DROIDDECK_PEN_CAP_PRESSURE')
        end = extension.index('\n};\n', extension.index('struct droiddeck_pen {', start))
        (folder / 'pen_api.inc').write_text(extension[start:end + 4])
        (folder / 'pen.inc').write_text(''.join(function(source, signature) for signature in (
            'static void input_output_to_scene(double px, double py, double *x, double *y) {',
            'static int pen_buttons(int flags) {',
            'static void pen_release(int buttons) {',
            'static void pen_end_route(uint32_t time_ms) {',
            'static void pen_pointer(const struct input_msg *m) {',
            'static uint32_t pen_axis(int value) {',
            'static void pen_tablet(const struct input_msg *m, int tool) {',
            'static void deliver_pen(const struct input_msg *m) {')))
        (folder / 'target.inc').write_text(
            function(source, 'static struct surface *pen_target_at(double x, double y) {')
            + function(source, 'static int pen_tablet_refused(struct wl_client *client) {'))
        start = source.index('#define for_each_touch_of(client, st)')
        (folder / 'touch.inc').write_text(source[start:source.index('\n\n', start) + 1])
        start = source.index('static pthread_mutex_t g_query_lock')
        (folder / 'query.inc').write_text(source[start:source.index('int droiddeck_cursor_input_pos(', start)])
        start = source.index('#define for_each_pointer_of(client, sp)')
        (folder / 'pointer.inc').write_text(
            function(source, 'static struct seat_pointer *pointer_for(struct wl_client *client) {')
            + source[start:source.index('#define for_each_keyboard_of', start)]
            + ''.join(function(source, signature) for signature in (
                'static void pointer_focus(struct wl_resource *target, wl_fixed_t fx, wl_fixed_t fy) {',
                'static void pointer_input(double x, double y, int relative, uint32_t button, int pressed) {',
                'static void pointer_event(double x, double y, uint32_t button, int pressed) {',
                'static void pointer_button(uint32_t button, int pressed) {')))
        cls.binary = folder / 'pen-test'
        cls.pointer_binary = folder / 'pen-pointer-test'
        cls.routing_binary = folder / 'pen-routing-test'
        for harness, binary in (('pen_emulation_harness.c', cls.binary),
                                ('pen_pointer_harness.c', cls.pointer_binary),
                                ('pen_routing_harness.c', cls.routing_binary)):
            subprocess.run([compiler, '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(folder),
                            str(REPO / 'tools/tests' / harness),
                            '-o', str(binary)], check=True, capture_output=True, text=True)

    def check_case(self, case, binary=None):
        result = subprocess.run([str(binary or self.binary), case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_flags_match_the_wire_contract(self):
        flags = dict(re.findall(r'^#define (PEN_[A-Z]+) \(1 << (\d)\)$', SOURCE.read_text(), re.MULTILINE))
        self.assertEqual(flags, {'PEN_IN': '0', 'PEN_TIP': '1', 'PEN_ERASER': '2', 'PEN_RIGHT': '3',
                                 'PEN_MIDDLE': '4', 'PEN_TABLET': '5', 'PEN_CANCEL': '6', 'PEN_TILT': '7'})

    def test_input_pipe_is_enlarged_for_pen_sample_rates(self):
        self.assertIn('fcntl(g_input_pipe[1], F_SETPIPE_SZ, 1 << 20);', SOURCE.read_text())

    def test_hover_moves_the_pointer_in_scene_coordinates(self):
        self.check_case('hover')

    def test_tip_presses_and_releases_the_left_button_once(self):
        self.check_case('tip')

    def test_eraser_contact_is_the_right_button(self):
        self.check_case('eraser')

    def test_right_and_middle_bits_press_after_motion_and_before_releases(self):
        self.check_case('buttons')

    def test_leaving_proximity_releases_every_button_without_motion(self):
        self.check_case('out')

    def test_cancel_releases_without_motion_and_ends_proximity(self):
        self.check_case('cancel')

    def test_pointer_lock_blocks_motion_but_passes_buttons(self):
        self.check_case('lock')

    def test_lost_records_still_release_held_buttons(self):
        self.check_case('lost')

    def test_relative_origin_resets_when_proximity_starts_and_ends(self):
        self.check_case('raw')

    def test_side_button_released_mid_stroke_keeps_the_grab_on_the_pressed_window(self):
        self.check_case('side', self.pointer_binary)

    def test_swapping_buttons_in_one_record_keeps_the_grab_on_the_pressed_window(self):
        self.check_case('swap', self.pointer_binary)

    def test_tip_drag_across_windows_stays_with_the_window_it_started_in(self):
        self.check_case('drag', self.pointer_binary)

    def test_a_window_closed_by_the_side_button_release_is_not_grabbed_again(self):
        self.check_case('gone', self.pointer_binary)

    def test_route_and_target_are_frozen_from_contact_to_the_last_release(self):
        self.check_case('frozen', self.routing_binary)

    def test_tablet_wish_without_a_tablet_client_is_the_pointer_and_announces_the_tool(self):
        self.check_case('notablet', self.routing_binary)

    def test_a_route_change_between_strokes_leaves_the_old_route_first(self):
        self.check_case('switch', self.routing_binary)

    def test_the_tablet_route_never_moves_the_pointer_and_sends_surface_local_axes(self):
        self.check_case('pointer', self.routing_binary)

    def test_the_pen_target_follows_the_pointer_order_and_the_tablet_ignores_locks(self):
        self.check_case('target', self.routing_binary)

    def test_a_stroke_whose_window_closed_sends_nothing_until_it_ends(self):
        self.check_case('orphan', self.routing_binary)

    def test_query_publishes_the_last_route_and_whether_the_pen_focus_takes_a_tablet(self):
        self.check_case('query', self.routing_binary)

    def test_gamescope_gets_the_pointer_even_with_a_tablet_and_only_buttons_under_its_lock(self):
        self.check_case('gamescope', self.routing_binary)

    def test_compositor_wires_the_tablet_into_surface_teardown_start_up_and_the_drain(self):
        source = SOURCE.read_text()
        teardown = function(source, 'static void surface_resource_destroy(struct wl_resource *r) {')
        self.assertIn('if (g_pen.target == s) g_pen.target = NULL;', teardown)
        self.assertIn('if (g_pen.grab == s) g_pen.grab = NULL;', teardown)
        self.assertIn('droiddeck_tablet_surface_gone(r);', teardown)
        drain = function(source, 'static int on_input_readable(int fd, uint32_t mask, void *data) {')
        self.assertIn('case 14: droiddeck_tablet_set_capabilities(atomic_load(&g_pen_caps)); break;', drain)
        self.assertIn('if (droiddeck_tablet_pending_flush()) wl_display_flush_clients(g_display);', drain)
        run = source[source.index('int droiddeck_wayland_run(void) {'):]
        self.assertLess(run.index('pipe2(g_input_pipe'), run.index('droiddeck_tablet_set_capabilities(atomic_load(&g_pen_caps));'))
        self.assertIn('tablet_init(display);', (REPO / 'app/src/main/cpp/waylandcomp/src/droiddeck_ext.c').read_text())


if __name__ == '__main__':
    unittest.main()
