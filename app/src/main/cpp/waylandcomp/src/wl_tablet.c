#define _GNU_SOURCE 1
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "droiddeck_ext.h"
#include "tablet-v2-server-protocol.h"

#ifndef BTN_TOOL_PEN
#define BTN_TOOL_PEN 0x140
#endif
#ifndef BTN_TOOL_RUBBER
#define BTN_TOOL_RUBBER 0x141
#endif
#ifndef BTN_STYLUS
#define BTN_STYLUS 0x14b
#endif
#ifndef BTN_STYLUS2
#define BTN_STYLUS2 0x14c
#endif

/* KWin keys its button rebinds and toolkits key per-device settings on this name, so it is never translated. */
#define TABLET_NAME "DroidDeck Pen"
#define TOOLS 2
#define SENT_TABLET 1u
#define SENT_TOOL(i) (2u << (i))

static const uint32_t k_buttons[] = { BTN_STYLUS2, BTN_STYLUS };
#define PEN_BUTTONS (int)(sizeof(k_buttons) / sizeof(k_buttons[0]))

struct bundle {
    struct wl_resource *seat, *tablet, *tools[TOOLS];
    unsigned sent, entered;
};

struct tool {
    uint32_t type;
    int known, caps;
    struct wl_list resources;
    struct wl_resource *focus;
    struct wl_client *client;
    uint32_t serial;
    int down, fresh;
    uint32_t buttons;
    wl_fixed_t x, y, tilt_x, tilt_y;
    uint32_t pressure, distance;
};

static struct wl_display *g_disp;
static struct wl_list g_seats;
static struct tool g_tools[TOOLS];
static struct tool *g_active;
static int g_caps, g_announced, g_sent;

static struct tool *tool_for(int tool) {
    if (tool == BTN_TOOL_PEN) return &g_tools[0];
    if (tool == BTN_TOOL_RUBBER) return &g_tools[1];
    return NULL;
}

static const char *tool_name(const struct tool *t) { return t == &g_tools[1] ? "eraser" : "pen"; }
static unsigned tool_bit(const struct tool *t) { return 1u << (t - g_tools); }

static struct bundle *live(struct wl_resource *tool_resource) {
    struct bundle *b = wl_resource_get_user_data(tool_resource);
    return b && b->tablet ? b : NULL;
}

static int has_live_resource(struct tool *t, struct wl_client *client) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources)
        if (wl_resource_get_client(r) == client && live(r)) return 1;
    return 0;
}

static int entered(struct tool *t, struct wl_resource *tool_resource) {
    struct bundle *b = live(tool_resource);
    return b && (b->entered & tool_bit(t));
}

static int has_entered_resource(struct tool *t) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources)
        if (entered(t, r)) return 1;
    return 0;
}

static void tool_drop_focus(struct tool *t) {
    struct wl_resource *r;
    wl_resource_for_each(r, &t->resources) {
        struct bundle *b = wl_resource_get_user_data(r);
        if (b) b->entered &= ~tool_bit(t);
    }
    t->focus = NULL;
    t->client = NULL;
    t->down = 0;
    t->buttons = 0;
    if (g_active == t) g_active = NULL;
}

static void forget_dead_focus(struct wl_client *client) {
    for (int i = 0; i < TOOLS; i++)
        if (g_tools[i].client == client && !has_entered_resource(&g_tools[i])) tool_drop_focus(&g_tools[i]);
}

static void bundle_release(struct bundle *b) {
    if (!b->seat && !b->tablet && !b->tools[0] && !b->tools[1]) free(b);
}

static void seat_res_destroy(struct wl_resource *r) {
    struct bundle *b = wl_resource_get_user_data(r);
    wl_list_remove(wl_resource_get_link(r));
    b->seat = NULL;
    bundle_release(b);
}

static void tablet_res_destroy(struct wl_resource *r) {
    struct bundle *b = wl_resource_get_user_data(r);
    b->tablet = NULL;
    forget_dead_focus(wl_resource_get_client(r));
    bundle_release(b);
}

static void tool_res_destroy(struct wl_resource *r) {
    struct bundle *b = wl_resource_get_user_data(r);
    wl_list_remove(wl_resource_get_link(r));
    for (int i = 0; i < TOOLS; i++)
        if (b->tools[i] == r) b->tools[i] = NULL;
    forget_dead_focus(wl_resource_get_client(r));
    bundle_release(b);
}

static void tablet_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_v2_interface tablet_impl = { .destroy = tablet_destroy };

static void tool_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                            struct wl_resource *surface, int32_t hx, int32_t hy) {
    struct bundle *b = wl_resource_get_user_data(r);
    for (int i = 0; i < TOOLS; i++) {
        struct tool *t = &g_tools[i];
        if (b->tools[i] != r || !t->focus || t->client != c || serial != t->serial || !entered(t, r)) continue;
        droiddeck_cursor_from_tool(surface, hx, hy);
    }
}
static void tool_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_tool_v2_interface tool_impl = {
    .set_cursor = tool_set_cursor, .destroy = tool_destroy,
};

static void seat_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_seat_v2_interface seat_impl = { .destroy = seat_destroy };

static void send_tablet(struct bundle *b) {
    struct wl_client *c = wl_resource_get_client(b->seat);
    int version = wl_resource_get_version(b->seat);
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_v2_interface, version, 0);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &tablet_impl, b, tablet_res_destroy);
    b->tablet = r;
    zwp_tablet_seat_v2_send_tablet_added(b->seat, r);
    zwp_tablet_v2_send_name(r, TABLET_NAME);
    if (version >= ZWP_TABLET_V2_BUSTYPE_SINCE_VERSION) zwp_tablet_v2_send_bustype(r, ZWP_TABLET_V2_BUSTYPE_VIRTUAL);
    zwp_tablet_v2_send_done(r);
    g_sent = 1;
}

static void send_tool(struct bundle *b, int i) {
    struct tool *t = &g_tools[i];
    struct wl_client *c = wl_resource_get_client(b->seat);
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_tool_v2_interface, wl_resource_get_version(b->seat), 0);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &tool_impl, b, tool_res_destroy);
    wl_list_insert(t->resources.prev, wl_resource_get_link(r));
    b->tools[i] = r;
    zwp_tablet_seat_v2_send_tool_added(b->seat, r);
    zwp_tablet_tool_v2_send_type(r, t->type);
    if (t->caps & DROIDDECK_PEN_CAP_TILT) zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_TILT);
    zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE);
    if (t->caps & DROIDDECK_PEN_CAP_DISTANCE)
        zwp_tablet_tool_v2_send_capability(r, ZWP_TABLET_TOOL_V2_CAPABILITY_DISTANCE);
    zwp_tablet_tool_v2_send_done(r);
    g_sent = 1;
}

static void announce(struct bundle *b) {
    if (!g_announced || !b->seat) return;
    if (!(b->sent & SENT_TABLET)) {
        b->sent |= SENT_TABLET;
        send_tablet(b);
    }
    if (!b->tablet) return;
    for (int i = 0; i < TOOLS; i++) {
        if (!g_tools[i].known || (b->sent & SENT_TOOL(i))) continue;
        b->sent |= SENT_TOOL(i);
        send_tool(b, i);
    }
}

static void announce_all(void) {
    struct wl_resource *r;
    wl_resource_for_each(r, &g_seats) announce(wl_resource_get_user_data(r));
}

static void manager_get_tablet_seat(struct wl_client *c, struct wl_resource *r, uint32_t id, struct wl_resource *seat) {
    struct bundle *b = calloc(1, sizeof(*b));
    if (!b) { wl_client_post_no_memory(c); return; }
    b->seat = wl_resource_create(c, &zwp_tablet_seat_v2_interface, wl_resource_get_version(r), id);
    if (!b->seat) { free(b); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(b->seat, &seat_impl, b, seat_res_destroy);
    wl_list_insert(g_seats.prev, wl_resource_get_link(b->seat));
    droiddeck_log("pen", "%s takes the pen as a tablet", droiddeck_client_name(c));
    announce(b);
}
static void manager_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct zwp_tablet_manager_v2_interface manager_impl = {
    .get_tablet_seat = manager_get_tablet_seat, .destroy = manager_destroy,
};
static void bind_tablet_manager(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_tablet_manager_v2_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &manager_impl, NULL, NULL);
}

void droiddeck_tablet_set_capabilities(int caps) {
    g_caps |= caps & (DROIDDECK_PEN_CAP_PRESSURE | DROIDDECK_PEN_CAP_TILT | DROIDDECK_PEN_CAP_DISTANCE);
    if (g_announced || !g_caps) return;
    g_announced = 1;
    droiddeck_log("pen", "the pen tablet \"%s\" is available to programs", TABLET_NAME);
    announce_all();
}

void droiddeck_tablet_ensure_tool(int tool) {
    struct tool *t = tool_for(tool);
    if (!t || t->known || !g_announced) return;
    t->known = 1;
    t->caps = g_caps;
    droiddeck_log("pen", "the pen tablet has a %s now (pressure%s%s)", tool_name(t),
                  t->caps & DROIDDECK_PEN_CAP_TILT ? ", tilt" : "", t->caps & DROIDDECK_PEN_CAP_DISTANCE ? ", distance" : "");
    announce_all();
}

int droiddeck_tablet_client_has_tool(struct wl_client *client, int tool) {
    struct tool *t = tool_for(tool);
    return t && client && has_live_resource(t, client);
}

int droiddeck_tablet_client_bound(struct wl_client *client) {
    return client && wl_resource_find_for_client(&g_seats, client) != NULL;
}

static void tool_leave(struct tool *t, uint32_t time_ms) {
    struct wl_resource *r;
    if (t->focus) {
        wl_resource_for_each(r, &t->resources) {
            if (!entered(t, r)) continue;
            if (t->down) zwp_tablet_tool_v2_send_up(r);
            for (int i = 0; i < PEN_BUTTONS; i++)
                if (t->buttons & (1u << i))
                    zwp_tablet_tool_v2_send_button(r, wl_display_next_serial(g_disp), k_buttons[i],
                                                   ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED);
            zwp_tablet_tool_v2_send_proximity_out(r);
            zwp_tablet_tool_v2_send_frame(r, time_ms);
            g_sent = 1;
        }
    }
    tool_drop_focus(t);
}

static int tool_enter(struct tool *t, struct wl_resource *surface) {
    struct wl_client *c = wl_resource_get_client(surface);
    struct wl_resource *r;
    if (!has_live_resource(t, c)) return 0;
    t->focus = surface;
    t->client = c;
    t->serial = wl_display_next_serial(g_disp);
    t->fresh = 1;
    g_active = t;
    wl_resource_for_each(r, &t->resources) {
        struct bundle *b = live(r);
        if (wl_resource_get_client(r) != c || !b) continue;
        b->entered |= tool_bit(t);
        zwp_tablet_tool_v2_send_proximity_in(r, t->serial, b->tablet, surface);
    }
    g_sent = 1;
    return 1;
}

void droiddeck_tablet_frame(struct wl_resource *surface, const struct droiddeck_pen *pen) {
    struct tool *t = tool_for(pen->tool);
    struct wl_resource *r;
    if (!t) return;
    if (g_active && g_active != t) tool_leave(g_active, pen->time_ms);
    if (!surface || t->focus != surface) {
        tool_leave(t, pen->time_ms);
        if (!surface || !tool_enter(t, surface)) return;
    }
    const wl_fixed_t x = wl_fixed_from_double(pen->x), y = wl_fixed_from_double(pen->y);
    const wl_fixed_t tx = pen->has_tilt ? wl_fixed_from_double(pen->tilt_x) : t->tilt_x;
    const wl_fixed_t ty = pen->has_tilt ? wl_fixed_from_double(pen->tilt_y) : t->tilt_y;
    const int motion = t->fresh || x != t->x || y != t->y;
    const int pressure = t->fresh || pen->pressure != t->pressure;
    const int distance = (t->caps & DROIDDECK_PEN_CAP_DISTANCE) && (t->fresh || pen->distance != t->distance);
    const int tilt = (t->caps & DROIDDECK_PEN_CAP_TILT) && (t->fresh || tx != t->tilt_x || ty != t->tilt_y);
    const int down = pen->down ? 1 : 0;
    wl_resource_for_each(r, &t->resources) {
        if (!entered(t, r)) continue;
        if (motion) zwp_tablet_tool_v2_send_motion(r, x, y);
        if (pressure) zwp_tablet_tool_v2_send_pressure(r, pen->pressure);
        if (distance) zwp_tablet_tool_v2_send_distance(r, pen->distance);
        if (tilt) zwp_tablet_tool_v2_send_tilt(r, tx, ty);
        if (down && !t->down) zwp_tablet_tool_v2_send_down(r, wl_display_next_serial(g_disp));
        else if (!down && t->down) zwp_tablet_tool_v2_send_up(r);
        for (int i = 0; i < PEN_BUTTONS; i++)
            if ((pen->buttons ^ t->buttons) & (1u << i))
                zwp_tablet_tool_v2_send_button(r, wl_display_next_serial(g_disp), k_buttons[i],
                                               pen->buttons & (1u << i) ? ZWP_TABLET_TOOL_V2_BUTTON_STATE_PRESSED
                                                                       : ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED);
        zwp_tablet_tool_v2_send_frame(r, pen->time_ms);
    }
    t->x = x; t->y = y;
    t->tilt_x = tx; t->tilt_y = ty;
    t->pressure = pen->pressure;
    t->distance = pen->distance;
    t->down = down;
    t->buttons = pen->buttons & ((1u << PEN_BUTTONS) - 1);
    t->fresh = 0;
    g_sent = 1;
}

void droiddeck_tablet_out(uint32_t time_ms) {
    if (g_active) tool_leave(g_active, time_ms);
}

void droiddeck_tablet_surface_gone(struct wl_resource *surface) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    const uint32_t now = (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
    for (int i = 0; i < TOOLS; i++)
        if (g_tools[i].focus == surface) tool_leave(&g_tools[i], now);
}

int droiddeck_tablet_pending_flush(void) {
    int sent = g_sent;
    g_sent = 0;
    return sent;
}

void tablet_init(struct wl_display *display) {
    g_disp = display;
    wl_list_init(&g_seats);
    for (int i = 0; i < TOOLS; i++) wl_list_init(&g_tools[i].resources);
    g_tools[0].type = ZWP_TABLET_TOOL_V2_TYPE_PEN;
    g_tools[1].type = ZWP_TABLET_TOOL_V2_TYPE_ERASER;
    wl_global_create(display, &zwp_tablet_manager_v2_interface, 2, NULL, bind_tablet_manager);
}
