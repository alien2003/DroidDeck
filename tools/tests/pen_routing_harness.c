#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define BTN_LEFT 0x110
#define BTN_RIGHT 0x111
#define BTN_MIDDLE 0x112
#define BTN_TOOL_PEN 0x140
#define BTN_TOOL_RUBBER 0x141
#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080
#define WL_POINTER_FRAME_SINCE_VERSION 5
#define WL_POINTER_BUTTON_STATE_RELEASED 0
#define WL_POINTER_BUTTON_STATE_PRESSED 1
#include "input_msg.inc"
#include "pen_state.inc"
#include "pen_api.inc"

typedef int32_t wl_fixed_t;
struct wl_client { int id; };
struct wl_resource { struct wl_client *client; };
struct wl_display { int unused; };
struct surface { struct wl_resource *resource; int placed, x, y; };
struct seat_pointer { struct wl_resource *ptr, *focus; };
struct seat_touch { struct wl_resource *touch, *focus; };
struct constraint { int is_lock; struct surface *surface; };

static struct wl_client client_a, client_b, client_d;
static struct wl_resource surface_a = {&client_a}, surface_b = {&client_b}, surface_d = {&client_d};
static struct wl_resource pointer_a = {&client_a}, pointer_b = {&client_b};
static struct surface a = {&surface_a, 1, 0, 0}, b = {&surface_b, 1, 1000, 0}, d = {&surface_d, 1, 300, 0};
static struct seat_pointer g_ptrs[2] = {{&pointer_a, NULL}, {&pointer_b, NULL}};
static int g_nptrs = 2;
static struct seat_touch g_touches[1];
static int g_ntouches;
static struct wl_display *g_display;
static struct constraint *g_active_constraint;
static struct surface *g_desktop, *g_grab, *g_key_target, *g_ime_click;
static double g_ptr_x, g_ptr_y, g_raw_x, g_raw_y;
static int g_ptr_known, g_raw_valid;
static atomic_int g_cursor_composite;

enum { ENTER, LEAVE, PRESS, RELEASE, FRAME, OUT };
struct event { int kind; struct wl_resource *pointer, *surface; uint32_t value; };
static struct event events[32];
static int nevents, motions, renders, ensures;
static struct droiddeck_pen last_pen;
static struct wl_client *tablet_taker, *gamescope;
static int tablet_caps, tools_known;

static void record(int kind, struct wl_resource *pointer, struct wl_resource *surface, uint32_t value) {
    assert(nevents < 32);
    events[nevents++] = (struct event){kind, pointer, surface, value};
}

static int tool_bit(int tool) { return tool == BTN_TOOL_PEN ? 1 : tool == BTN_TOOL_RUBBER ? 2 : 0; }
static void droiddeck_tablet_set_capabilities(int caps) { tablet_caps |= caps; }
static void droiddeck_tablet_ensure_tool(int tool) {
    ensures++;
    if (tablet_caps) tools_known |= tool_bit(tool);
}
static int droiddeck_tablet_client_has_tool(struct wl_client *client, int tool) {
    return client == tablet_taker && (tools_known & tool_bit(tool));
}
static int droiddeck_tablet_client_bound(struct wl_client *client) { return client == tablet_taker; }
static void droiddeck_tablet_frame(struct wl_resource *surface, const struct droiddeck_pen *pen) {
    record(FRAME, NULL, surface, pen->time_ms);
    last_pen = *pen;
}
static void droiddeck_tablet_out(uint32_t time_ms) { record(OUT, NULL, NULL, time_ms); }
static const char *client_name(struct wl_client *client) { return client == gamescope ? "gamescope" : "a program"; }

static void scene_size(int *w, int *h) { *w = 2000; *h = 1000; }
static void vkp_output_size(int *w, int *h) { *w = 0; *h = 0; }
static int vkp_output_to_scene(double ox, double oy, double *x, double *y) {
    (void)ox; (void)oy; (void)x; (void)y;
    return 0;
}
static int vkp_scene_to_output(double x, double y, double *ox, double *oy) {
    (void)x; (void)y; (void)ox; (void)oy;
    return 0;
}
static struct surface *toplevel_at(double x, double y) {
    (void)y;
    return x < 1000 ? &a : &b;
}
static void confine_clamp(struct constraint *k, double *x, double *y) { (void)k; (void)x; (void)y; }
static void schedule_render(void) { renders++; }
static void constraints_focus_entered(struct wl_resource *target, struct wl_client *client) {
    (void)target; (void)client;
}
static void droiddeck_text_input_refocus(void) {}
static void pen_log_target(void) {}
static void wl_display_flush_clients(struct wl_display *display) { (void)display; }
static uint32_t now_ms(void) { return 0; }
static uint32_t wl_display_next_serial(struct wl_display *display) { (void)display; return 0; }
static int wl_resource_get_version(struct wl_resource *r) { (void)r; return 7; }
static struct wl_client *wl_resource_get_client(struct wl_resource *r) { return r->client; }
static wl_fixed_t wl_fixed_from_double(double v) { return (wl_fixed_t)(v * 256); }
static int send_relative_motion(struct wl_client *client, double dx, double dy) {
    (void)client; (void)dx; (void)dy;
    return 0;
}
static void wl_pointer_send_enter(struct wl_resource *p, uint32_t serial, struct wl_resource *s,
                                  wl_fixed_t x, wl_fixed_t y) {
    (void)serial; (void)x; (void)y;
    record(ENTER, p, s, 0);
}
static void wl_pointer_send_leave(struct wl_resource *p, uint32_t serial, struct wl_resource *s) {
    (void)serial;
    record(LEAVE, p, s, 0);
}
static void wl_pointer_send_motion(struct wl_resource *p, uint32_t t, wl_fixed_t x, wl_fixed_t y) {
    (void)p; (void)t; (void)x; (void)y;
    motions++;
}
static void wl_pointer_send_frame(struct wl_resource *p) { (void)p; }
static void wl_pointer_send_button(struct wl_resource *p, uint32_t serial, uint32_t t, uint32_t button,
                                   uint32_t state) {
    (void)serial; (void)t;
    record(state == WL_POINTER_BUTTON_STATE_PRESSED ? PRESS : RELEASE, p, NULL, button);
}
#include "touch.inc"
#include "target.inc"
#include "query.inc"
#include "pointer.inc"
#include "pen.inc"

static void send_pen(int flags, double x, double y, int pressure, int distance, int tilt_x, int tilt_y, int time) {
    struct input_msg m = {13, flags, (int)(x * INPUT_SPACE_W / 2000 * 256), (int)(y * INPUT_SPACE_H / 1000 * 256),
                          pressure, distance, tilt_x, tilt_y, time};
    nevents = 0;
    deliver_pen(&m);
}

static void pen(int flags, double x) { send_pen(flags, x, 100, 0, 0, 0, 0, 1); }

static double scene_x(double x) { return (int)(x * INPUT_SPACE_W / 2000 * 256) / 256.0 * 2000 / INPUT_SPACE_W; }

static void expect(int i, int kind, struct wl_resource *pointer, struct wl_resource *surface) {
    assert(i < nevents && events[i].kind == kind && events[i].pointer == pointer && events[i].surface == surface);
}

#define T (PEN_IN | PEN_TABLET)

static void frozen(void) {
    atomic_store(&g_pen_caps, 1);
    tablet_taker = &client_b;
    pen(T, 500);
    assert(nevents == 1 && ensures == 1 && g_pen.route == PEN_ROUTE_POINTER);
    expect(0, ENTER, &pointer_a, &surface_a);
    pen(T | PEN_TIP, 500);
    assert(nevents == 1 && events[0].kind == PRESS && events[0].value == BTN_LEFT && g_grab == &a);
    pen(T | PEN_TIP, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_POINTER && ensures == 2);
    pen(T, 1500);
    assert(nevents == 1 && events[0].kind == RELEASE && events[0].value == BTN_LEFT && !g_grab);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET && g_pen.target == &b && ensures == 3);
    expect(0, FRAME, NULL, &surface_b);
    assert(last_pen.x == 500 && last_pen.y == 100 && !last_pen.down);
    pen(T | PEN_TIP, 1500);
    assert(nevents == 1 && last_pen.down);
    expect(0, FRAME, NULL, &surface_b);
    pen(T | PEN_TIP, 500);
    assert(nevents == 1 && last_pen.down && last_pen.x == -500 && ensures == 4);
    expect(0, FRAME, NULL, &surface_b);
    pen(T, 500);
    assert(nevents == 1 && !last_pen.down);
    expect(0, FRAME, NULL, &surface_b);
    int before = motions;
    pen(T, 500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_POINTER && motions == before + 1);
    expect(0, OUT, NULL, NULL);
}

static void no_tablet_client(void) {
    atomic_store(&g_pen_caps, 3);
    pen(PEN_IN, 1500);
    assert(ensures == 0 && tablet_caps == 0 && nevents == 1);
    expect(0, ENTER, &pointer_b, &surface_b);
    pen(T, 1500);
    assert(ensures == 1 && tablet_caps == 3 && tools_known == 1 && nevents == 0);
    pen(T | PEN_ERASER | PEN_TIP, 1500);
    assert(ensures == 2 && tools_known == 3 && nevents == 1 && events[0].kind == PRESS && events[0].value == BTN_RIGHT);
    pen(T | PEN_ERASER, 1500);
    assert(nevents == 1 && events[0].kind == RELEASE && events[0].value == BTN_RIGHT);
    pen(T | PEN_ERASER, 1500);
    pen(T, 1500);
    pen(0, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_NONE);
    tablet_taker = &client_b;
    tablet_caps = tools_known = 0;
    atomic_store(&g_pen_caps, 0);
    pen(T, 1500);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_POINTER && !tools_known);
    pen(0, 1500);
    atomic_store(&g_pen_caps, 1);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_TABLET);
    expect(0, FRAME, NULL, &surface_b);
}

static void switch_between_strokes(void) {
    atomic_store(&g_pen_caps, 1);
    tablet_taker = &client_b;
    pen(T, 1500);
    assert(nevents == 1);
    expect(0, FRAME, NULL, &surface_b);
    pen(T, 500);
    assert(nevents == 2);
    expect(0, OUT, NULL, NULL);
    expect(1, ENTER, &pointer_a, &surface_a);
    pen(T | PEN_RIGHT, 500);
    assert(nevents == 1 && events[0].kind == PRESS && events[0].value == BTN_RIGHT);
    pen(T, 500);
    assert(nevents == 1 && events[0].kind == RELEASE && events[0].value == BTN_RIGHT);
    pen(T, 1500);
    assert(nevents == 1 && g_pen.buttons == 0);
    expect(0, FRAME, NULL, &surface_b);
    pen(PEN_IN, 1500);
    assert(nevents == 3);
    expect(0, OUT, NULL, NULL);
    expect(1, LEAVE, &pointer_a, &surface_a);
    expect(2, ENTER, &pointer_b, &surface_b);
    pen(T, 1500);
    pen(T | PEN_CANCEL, 1500);
    assert(nevents == 1 && g_pen.route == PEN_ROUTE_NONE);
    expect(0, OUT, NULL, NULL);
    pen(0, 1500);
    assert(nevents == 0);
    pen(T, 500);
    assert(nevents == 2);
    expect(0, LEAVE, &pointer_b, &surface_b);
    expect(1, ENTER, &pointer_a, &surface_a);
    pen(T | PEN_TIP, 500);
    pen(T | PEN_CANCEL | PEN_TIP, 500);
    assert(nevents == 1 && events[0].kind == RELEASE && events[0].value == BTN_LEFT && g_pen.route == PEN_ROUTE_NONE);
}

static void tablet_leaves_the_pointer_alone(void) {
    atomic_store(&g_pen_caps, 3);
    tablet_taker = &client_b;
    atomic_store(&g_cursor_composite, 1);
    g_ptr_x = 123; g_ptr_y = 45; g_ptr_known = 0;
    g_raw_valid = 1; g_raw_x = 7; g_raw_y = 8;
    send_pen(T | PEN_TILT | PEN_TIP | PEN_RIGHT | PEN_MIDDLE, 1500, 200, 40000, -5, -4500, 9000, 77);
    assert(nevents == 1);
    expect(0, FRAME, NULL, &surface_b);
    assert(last_pen.tool == BTN_TOOL_PEN && last_pen.x == 500 && last_pen.y == 200);
    assert(last_pen.pressure == 40000 && last_pen.distance == 0 && last_pen.has_tilt);
    assert(last_pen.tilt_x == -45 && last_pen.tilt_y == 90 && last_pen.down);
    assert(last_pen.buttons == (DROIDDECK_PEN_RIGHT | DROIDDECK_PEN_MIDDLE) && last_pen.time_ms == 77);
    send_pen(T | PEN_ERASER | PEN_RIGHT, 1600.5, 125, 70000, 70000, 100, 100, -1);
    assert(nevents == 1);
    expect(0, FRAME, NULL, &surface_b);
    assert(last_pen.tool == BTN_TOOL_RUBBER && last_pen.x == scene_x(1600.5) - 1000 && last_pen.y == 125);
    assert(last_pen.x != (int)last_pen.x);
    assert(last_pen.pressure == 65535 && last_pen.distance == 65535 && !last_pen.has_tilt && !last_pen.down);
    assert(last_pen.buttons == DROIDDECK_PEN_RIGHT && last_pen.time_ms == UINT32_MAX);
    pen(0, 1600);
    assert(nevents == 1);
    expect(0, OUT, NULL, NULL);
    assert(g_ptr_x == 123 && g_ptr_y == 45 && !g_ptr_known && !renders && !motions && !g_grab && !g_key_target);
    assert(g_raw_valid && g_raw_x == 7 && g_raw_y == 8 && !g_ptrs[0].focus && !g_ptrs[1].focus);
}

static void target_order(void) {
    atomic_store(&g_pen_caps, 1);
    struct constraint lock = {1, &a};
    g_active_constraint = &lock;
    g_desktop = &d;
    g_grab = &b;
    tablet_taker = &client_a;
    pen(T, 1500);
    assert(nevents == 1 && last_pen.x == 1500);
    expect(0, FRAME, NULL, &surface_a);
    pen(0, 1500);
    tablet_taker = NULL;
    pen(T, 1500);
    assert(nevents == 0 && !motions && g_pen.route == PEN_ROUTE_POINTER);
    pen(T | PEN_TIP, 1500);
    assert(nevents == 2 && events[1].kind == PRESS && events[1].pointer == &pointer_a && !motions);
    pen(0, 1500);
    assert(!g_grab);
    g_active_constraint = NULL;
    g_grab = &b;
    tablet_taker = &client_d;
    pen(T, 500);
    assert(nevents == 1 && last_pen.x == 500 && g_pen.target == &d);
    expect(0, FRAME, NULL, &surface_d);
    pen(0, 500);
    g_desktop = NULL;
    g_grab = &a;
    tablet_taker = &client_a;
    pen(T, 1500);
    assert(nevents == 1 && last_pen.x == 1500 && g_pen.target == &a);
    expect(0, FRAME, NULL, &surface_a);
    g_grab = NULL;
    pen(T, 1500);
    assert(nevents == 3 && g_pen.route == PEN_ROUTE_POINTER);
    expect(0, OUT, NULL, NULL);
    expect(1, LEAVE, &pointer_a, &surface_a);
    expect(2, ENTER, &pointer_b, &surface_b);
}

static void surface_gone_orphans_the_stroke(void) {
    atomic_store(&g_pen_caps, 1);
    tablet_taker = &client_b;
    pen(T | PEN_TIP, 1500);
    assert(nevents == 1 && g_pen.target == &b);
    if (g_pen.target == &b) g_pen.target = NULL;
    pen(T | PEN_TIP, 1600);
    assert(nevents == 0 && g_pen.route == PEN_ROUTE_TABLET);
    pen(T, 1600);
    assert(nevents == 0);
    pen(T, 1600);
    assert(nevents == 1 && g_pen.target == &b && !last_pen.down);
    expect(0, FRAME, NULL, &surface_b);
}

static void query_reports_route_and_tablet_focus(void) {
    atomic_store(&g_pen_caps, 1);
    g_ptr_x = 1500;
    tablet_taker = &client_b;
    publish_query_state();
    assert(droiddeck_pen_route() == PEN_ROUTE_TABLET && g_query.pen_route == PEN_ROUTE_NONE);
    tablet_taker = &client_a;
    publish_query_state();
    assert(droiddeck_pen_route() == PEN_ROUTE_POINTER);
    pen(T, 500);
    assert(g_pen.route == PEN_ROUTE_TABLET && g_query.pen_route == PEN_ROUTE_TABLET);
    assert(droiddeck_pen_route() == PEN_ROUTE_TABLET);
    pen(0, 500);
    assert(g_query.pen_route == PEN_ROUTE_TABLET && droiddeck_pen_route() == PEN_ROUTE_TABLET);
    tablet_taker = &client_b;
    publish_query_state();
    assert(droiddeck_pen_route() == PEN_ROUTE_POINTER);
    pen(PEN_IN, 1500);
    assert(g_query.pen_route == PEN_ROUTE_POINTER && droiddeck_pen_route() == PEN_ROUTE_TABLET);
    assert(g_query.has_position && g_query.input_x == 1440);
}

static void gamescope_takes_the_pointer(void) {
    atomic_store(&g_pen_caps, 1);
    struct constraint lock = {1, &a};
    g_active_constraint = &lock;
    tablet_taker = gamescope = &client_a;
    pen(T, 1500);
    assert(nevents == 0 && !motions && g_pen.route == PEN_ROUTE_POINTER && tools_known == 1);
    assert(droiddeck_pen_route() == PEN_ROUTE_POINTER);
    pen(T, 1200);
    assert(nevents == 0 && !motions);
    pen(T | PEN_TIP, 1200);
    assert(nevents == 2 && !motions && g_pen.route == PEN_ROUTE_POINTER);
    expect(0, ENTER, &pointer_a, &surface_a);
    assert(events[1].kind == PRESS && events[1].pointer == &pointer_a && events[1].value == BTN_LEFT);
    pen(T, 1200);
    assert(nevents == 1 && events[0].kind == RELEASE && events[0].value == BTN_LEFT && !motions);
    pen(0, 1200);
    g_active_constraint = NULL;
    pen(T, 500);
    assert(nevents == 0 && motions == 1 && g_pen.route == PEN_ROUTE_POINTER);
    assert(droiddeck_pen_route() == PEN_ROUTE_POINTER);
    gamescope = NULL;
    pen(T, 500);
    assert(nevents == 1 && motions == 1 && g_pen.route == PEN_ROUTE_TABLET);
    expect(0, FRAME, NULL, &surface_a);
    assert(droiddeck_pen_route() == PEN_ROUTE_TABLET);
}

int main(int argc, char **argv) {
    static const struct { const char *name; void (*run)(void); } cases[] = {
        {"frozen", frozen}, {"notablet", no_tablet_client}, {"switch", switch_between_strokes},
        {"pointer", tablet_leaves_the_pointer_alone}, {"target", target_order},
        {"orphan", surface_gone_orphans_the_stroke}, {"query", query_reports_route_and_tablet_focus},
        {"gamescope", gamescope_takes_the_pointer},
    };
    assert(argc == 2);
    for (size_t i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        if (strcmp(argv[1], cases[i].name)) continue;
        cases[i].run();
        puts("PASS");
        return 0;
    }
    return 2;
}
