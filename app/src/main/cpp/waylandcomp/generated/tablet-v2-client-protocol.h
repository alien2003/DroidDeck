
#ifndef TABLET_V2_CLIENT_PROTOCOL_H
#define TABLET_V2_CLIENT_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include "wayland-client.h"

#ifdef  __cplusplus
extern "C" {
#endif

/*
 * @section page_copyright_tablet_v2 Copyright
 *
 * Copyright 2014 © Stephen "Lyude" Chandler Paul
 * Copyright 2015-2024 © Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation files
 * (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice (including the
 * next paragraph) shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */






























































































struct wl_seat;
struct wl_surface;
struct zwp_tablet_manager_v2;
struct zwp_tablet_pad_dial_v2;
struct zwp_tablet_pad_group_v2;
struct zwp_tablet_pad_ring_v2;
struct zwp_tablet_pad_strip_v2;
struct zwp_tablet_pad_v2;
struct zwp_tablet_seat_v2;
struct zwp_tablet_tool_v2;
struct zwp_tablet_v2;

#ifndef ZWP_TABLET_MANAGER_V2_INTERFACE
#define ZWP_TABLET_MANAGER_V2_INTERFACE
extern const struct wl_interface zwp_tablet_manager_v2_interface;
#endif
#ifndef ZWP_TABLET_SEAT_V2_INTERFACE
#define ZWP_TABLET_SEAT_V2_INTERFACE
extern const struct wl_interface zwp_tablet_seat_v2_interface;
#endif
#ifndef ZWP_TABLET_TOOL_V2_INTERFACE
#define ZWP_TABLET_TOOL_V2_INTERFACE
extern const struct wl_interface zwp_tablet_tool_v2_interface;
#endif
#ifndef ZWP_TABLET_V2_INTERFACE
#define ZWP_TABLET_V2_INTERFACE
extern const struct wl_interface zwp_tablet_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_RING_V2_INTERFACE
#define ZWP_TABLET_PAD_RING_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_ring_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_STRIP_V2_INTERFACE
#define ZWP_TABLET_PAD_STRIP_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_strip_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_GROUP_V2_INTERFACE
#define ZWP_TABLET_PAD_GROUP_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_group_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_V2_INTERFACE
#define ZWP_TABLET_PAD_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_v2_interface;
#endif
#ifndef ZWP_TABLET_PAD_DIAL_V2_INTERFACE
#define ZWP_TABLET_PAD_DIAL_V2_INTERFACE
extern const struct wl_interface zwp_tablet_pad_dial_v2_interface;
#endif

#define ZWP_TABLET_MANAGER_V2_GET_TABLET_SEAT 0
#define ZWP_TABLET_MANAGER_V2_DESTROY 1


#define ZWP_TABLET_MANAGER_V2_GET_TABLET_SEAT_SINCE_VERSION 1
#define ZWP_TABLET_MANAGER_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_manager_v2_set_user_data(struct zwp_tablet_manager_v2 *zwp_tablet_manager_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_manager_v2, user_data);
}

static inline void *
zwp_tablet_manager_v2_get_user_data(struct zwp_tablet_manager_v2 *zwp_tablet_manager_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_manager_v2);
}

static inline uint32_t
zwp_tablet_manager_v2_get_version(struct zwp_tablet_manager_v2 *zwp_tablet_manager_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_manager_v2);
}

static inline struct zwp_tablet_seat_v2 *
zwp_tablet_manager_v2_get_tablet_seat(struct zwp_tablet_manager_v2 *zwp_tablet_manager_v2, struct wl_seat *seat)
{
	struct wl_proxy *tablet_seat;

	tablet_seat = wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_manager_v2,
			 ZWP_TABLET_MANAGER_V2_GET_TABLET_SEAT, &zwp_tablet_seat_v2_interface, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_manager_v2), 0, NULL, seat);

	return (struct zwp_tablet_seat_v2 *) tablet_seat;
}

static inline void
zwp_tablet_manager_v2_destroy(struct zwp_tablet_manager_v2 *zwp_tablet_manager_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_manager_v2,
			 ZWP_TABLET_MANAGER_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_manager_v2), WL_MARSHAL_FLAG_DESTROY);
}

struct zwp_tablet_seat_v2_listener {
	void (*tablet_added)(void *data,
			     struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2,
			     struct zwp_tablet_v2 *id);
	void (*tool_added)(void *data,
			   struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2,
			   struct zwp_tablet_tool_v2 *id);
	void (*pad_added)(void *data,
			  struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2,
			  struct zwp_tablet_pad_v2 *id);
};

static inline int
zwp_tablet_seat_v2_add_listener(struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2,
				const struct zwp_tablet_seat_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_seat_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_SEAT_V2_DESTROY 0

#define ZWP_TABLET_SEAT_V2_TABLET_ADDED_SINCE_VERSION 1
#define ZWP_TABLET_SEAT_V2_TOOL_ADDED_SINCE_VERSION 1
#define ZWP_TABLET_SEAT_V2_PAD_ADDED_SINCE_VERSION 1

#define ZWP_TABLET_SEAT_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_seat_v2_set_user_data(struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_seat_v2, user_data);
}

static inline void *
zwp_tablet_seat_v2_get_user_data(struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_seat_v2);
}

static inline uint32_t
zwp_tablet_seat_v2_get_version(struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_seat_v2);
}

static inline void
zwp_tablet_seat_v2_destroy(struct zwp_tablet_seat_v2 *zwp_tablet_seat_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_seat_v2,
			 ZWP_TABLET_SEAT_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_seat_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef ZWP_TABLET_TOOL_V2_TYPE_ENUM
#define ZWP_TABLET_TOOL_V2_TYPE_ENUM
enum zwp_tablet_tool_v2_type {
	ZWP_TABLET_TOOL_V2_TYPE_PEN = 0x140,
	ZWP_TABLET_TOOL_V2_TYPE_ERASER = 0x141,
	ZWP_TABLET_TOOL_V2_TYPE_BRUSH = 0x142,
	ZWP_TABLET_TOOL_V2_TYPE_PENCIL = 0x143,
	ZWP_TABLET_TOOL_V2_TYPE_AIRBRUSH = 0x144,
	ZWP_TABLET_TOOL_V2_TYPE_FINGER = 0x145,
	ZWP_TABLET_TOOL_V2_TYPE_MOUSE = 0x146,
	ZWP_TABLET_TOOL_V2_TYPE_LENS = 0x147,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM
#define ZWP_TABLET_TOOL_V2_CAPABILITY_ENUM
enum zwp_tablet_tool_v2_capability {
	ZWP_TABLET_TOOL_V2_CAPABILITY_TILT = 1,
	ZWP_TABLET_TOOL_V2_CAPABILITY_PRESSURE = 2,
	ZWP_TABLET_TOOL_V2_CAPABILITY_DISTANCE = 3,
	ZWP_TABLET_TOOL_V2_CAPABILITY_ROTATION = 4,
	ZWP_TABLET_TOOL_V2_CAPABILITY_SLIDER = 5,
	ZWP_TABLET_TOOL_V2_CAPABILITY_WHEEL = 6,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM
#define ZWP_TABLET_TOOL_V2_BUTTON_STATE_ENUM
enum zwp_tablet_tool_v2_button_state {
	ZWP_TABLET_TOOL_V2_BUTTON_STATE_RELEASED = 0,
	ZWP_TABLET_TOOL_V2_BUTTON_STATE_PRESSED = 1,
};
#endif

#ifndef ZWP_TABLET_TOOL_V2_ERROR_ENUM
#define ZWP_TABLET_TOOL_V2_ERROR_ENUM
enum zwp_tablet_tool_v2_error {
	ZWP_TABLET_TOOL_V2_ERROR_ROLE = 0,
};
#endif

struct zwp_tablet_tool_v2_listener {
	void (*type)(void *data,
		     struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		     uint32_t tool_type);
	void (*hardware_serial)(void *data,
				struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
				uint32_t hardware_serial_hi,
				uint32_t hardware_serial_lo);
	void (*hardware_id_wacom)(void *data,
				  struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
				  uint32_t hardware_id_hi,
				  uint32_t hardware_id_lo);
	void (*capability)(void *data,
			   struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
			   uint32_t capability);
	void (*done)(void *data,
		     struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2);
	void (*removed)(void *data,
			struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2);
	void (*proximity_in)(void *data,
			     struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
			     uint32_t serial,
			     struct zwp_tablet_v2 *tablet,
			     struct wl_surface *surface);
	void (*proximity_out)(void *data,
			      struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2);
	void (*down)(void *data,
		     struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		     uint32_t serial);
	void (*up)(void *data,
		   struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2);
	void (*motion)(void *data,
		       struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		       wl_fixed_t x,
		       wl_fixed_t y);
	void (*pressure)(void *data,
			 struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
			 uint32_t pressure);
	void (*distance)(void *data,
			 struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
			 uint32_t distance);
	void (*tilt)(void *data,
		     struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		     wl_fixed_t tilt_x,
		     wl_fixed_t tilt_y);
	void (*rotation)(void *data,
			 struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
			 wl_fixed_t degrees);
	void (*slider)(void *data,
		       struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		       int32_t position);
	void (*wheel)(void *data,
		      struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		      wl_fixed_t degrees,
		      int32_t clicks);
	void (*button)(void *data,
		       struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		       uint32_t serial,
		       uint32_t button,
		       uint32_t state);
	void (*frame)(void *data,
		      struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
		      uint32_t time);
};

static inline int
zwp_tablet_tool_v2_add_listener(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2,
				const struct zwp_tablet_tool_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_tool_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_TOOL_V2_SET_CURSOR 0
#define ZWP_TABLET_TOOL_V2_DESTROY 1

#define ZWP_TABLET_TOOL_V2_TYPE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_HARDWARE_SERIAL_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_HARDWARE_ID_WACOM_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_CAPABILITY_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_REMOVED_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PROXIMITY_IN_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PROXIMITY_OUT_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DOWN_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_UP_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_MOTION_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_PRESSURE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DISTANCE_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_TILT_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_ROTATION_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_SLIDER_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_WHEEL_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_BUTTON_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_TOOL_V2_SET_CURSOR_SINCE_VERSION 1
#define ZWP_TABLET_TOOL_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_tool_v2_set_user_data(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_tool_v2, user_data);
}

static inline void *
zwp_tablet_tool_v2_get_user_data(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_tool_v2);
}

static inline uint32_t
zwp_tablet_tool_v2_get_version(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_tool_v2);
}

static inline void
zwp_tablet_tool_v2_set_cursor(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2, uint32_t serial, struct wl_surface *surface, int32_t hotspot_x, int32_t hotspot_y)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_tool_v2,
			 ZWP_TABLET_TOOL_V2_SET_CURSOR, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_tool_v2), 0, serial, surface, hotspot_x, hotspot_y);
}

static inline void
zwp_tablet_tool_v2_destroy(struct zwp_tablet_tool_v2 *zwp_tablet_tool_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_tool_v2,
			 ZWP_TABLET_TOOL_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_tool_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef ZWP_TABLET_V2_BUSTYPE_ENUM
#define ZWP_TABLET_V2_BUSTYPE_ENUM
enum zwp_tablet_v2_bustype {
	ZWP_TABLET_V2_BUSTYPE_USB = 3,
	ZWP_TABLET_V2_BUSTYPE_BLUETOOTH = 5,
	ZWP_TABLET_V2_BUSTYPE_VIRTUAL = 6,
	ZWP_TABLET_V2_BUSTYPE_SERIAL = 17,
	ZWP_TABLET_V2_BUSTYPE_I2C = 24,
};
#endif

struct zwp_tablet_v2_listener {
	void (*name)(void *data,
		     struct zwp_tablet_v2 *zwp_tablet_v2,
		     const char *name);
	void (*id)(void *data,
		   struct zwp_tablet_v2 *zwp_tablet_v2,
		   uint32_t vid,
		   uint32_t pid);
	void (*path)(void *data,
		     struct zwp_tablet_v2 *zwp_tablet_v2,
		     const char *path);
	void (*done)(void *data,
		     struct zwp_tablet_v2 *zwp_tablet_v2);
	void (*removed)(void *data,
			struct zwp_tablet_v2 *zwp_tablet_v2);
	void (*bustype)(void *data,
			struct zwp_tablet_v2 *zwp_tablet_v2,
			uint32_t bustype);
};

static inline int
zwp_tablet_v2_add_listener(struct zwp_tablet_v2 *zwp_tablet_v2,
			   const struct zwp_tablet_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_V2_DESTROY 0

#define ZWP_TABLET_V2_NAME_SINCE_VERSION 1
#define ZWP_TABLET_V2_ID_SINCE_VERSION 1
#define ZWP_TABLET_V2_PATH_SINCE_VERSION 1
#define ZWP_TABLET_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_V2_REMOVED_SINCE_VERSION 1
#define ZWP_TABLET_V2_BUSTYPE_SINCE_VERSION 2

#define ZWP_TABLET_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_v2_set_user_data(struct zwp_tablet_v2 *zwp_tablet_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_v2, user_data);
}

static inline void *
zwp_tablet_v2_get_user_data(struct zwp_tablet_v2 *zwp_tablet_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_v2);
}

static inline uint32_t
zwp_tablet_v2_get_version(struct zwp_tablet_v2 *zwp_tablet_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_v2);
}

static inline void
zwp_tablet_v2_destroy(struct zwp_tablet_v2 *zwp_tablet_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_v2,
			 ZWP_TABLET_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM
#define ZWP_TABLET_PAD_RING_V2_SOURCE_ENUM
enum zwp_tablet_pad_ring_v2_source {
	ZWP_TABLET_PAD_RING_V2_SOURCE_FINGER = 1,
};
#endif

struct zwp_tablet_pad_ring_v2_listener {
	void (*source)(void *data,
		       struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2,
		       uint32_t source);
	void (*angle)(void *data,
		      struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2,
		      wl_fixed_t degrees);
	void (*stop)(void *data,
		     struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2);
	void (*frame)(void *data,
		      struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2,
		      uint32_t time);
};

static inline int
zwp_tablet_pad_ring_v2_add_listener(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2,
				    const struct zwp_tablet_pad_ring_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_pad_ring_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_PAD_RING_V2_SET_FEEDBACK 0
#define ZWP_TABLET_PAD_RING_V2_DESTROY 1

#define ZWP_TABLET_PAD_RING_V2_SOURCE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_ANGLE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_STOP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_RING_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_RING_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_ring_v2_set_user_data(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_pad_ring_v2, user_data);
}

static inline void *
zwp_tablet_pad_ring_v2_get_user_data(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_pad_ring_v2);
}

static inline uint32_t
zwp_tablet_pad_ring_v2_get_version(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_ring_v2);
}

static inline void
zwp_tablet_pad_ring_v2_set_feedback(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2, const char *description, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_ring_v2,
			 ZWP_TABLET_PAD_RING_V2_SET_FEEDBACK, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_ring_v2), 0, description, serial);
}

static inline void
zwp_tablet_pad_ring_v2_destroy(struct zwp_tablet_pad_ring_v2 *zwp_tablet_pad_ring_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_ring_v2,
			 ZWP_TABLET_PAD_RING_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_ring_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM
#define ZWP_TABLET_PAD_STRIP_V2_SOURCE_ENUM
enum zwp_tablet_pad_strip_v2_source {
	ZWP_TABLET_PAD_STRIP_V2_SOURCE_FINGER = 1,
};
#endif

struct zwp_tablet_pad_strip_v2_listener {
	void (*source)(void *data,
		       struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2,
		       uint32_t source);
	void (*position)(void *data,
			 struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2,
			 uint32_t position);
	void (*stop)(void *data,
		     struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2);
	void (*frame)(void *data,
		      struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2,
		      uint32_t time);
};

static inline int
zwp_tablet_pad_strip_v2_add_listener(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2,
				     const struct zwp_tablet_pad_strip_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_pad_strip_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_PAD_STRIP_V2_SET_FEEDBACK 0
#define ZWP_TABLET_PAD_STRIP_V2_DESTROY 1

#define ZWP_TABLET_PAD_STRIP_V2_SOURCE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_POSITION_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_STOP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_STRIP_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_STRIP_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_strip_v2_set_user_data(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_pad_strip_v2, user_data);
}

static inline void *
zwp_tablet_pad_strip_v2_get_user_data(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_pad_strip_v2);
}

static inline uint32_t
zwp_tablet_pad_strip_v2_get_version(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_strip_v2);
}

static inline void
zwp_tablet_pad_strip_v2_set_feedback(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2, const char *description, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_strip_v2,
			 ZWP_TABLET_PAD_STRIP_V2_SET_FEEDBACK, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_strip_v2), 0, description, serial);
}

static inline void
zwp_tablet_pad_strip_v2_destroy(struct zwp_tablet_pad_strip_v2 *zwp_tablet_pad_strip_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_strip_v2,
			 ZWP_TABLET_PAD_STRIP_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_strip_v2), WL_MARSHAL_FLAG_DESTROY);
}

struct zwp_tablet_pad_group_v2_listener {
	void (*buttons)(void *data,
			struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
			struct wl_array *buttons);
	void (*ring)(void *data,
		     struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
		     struct zwp_tablet_pad_ring_v2 *ring);
	void (*strip)(void *data,
		      struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
		      struct zwp_tablet_pad_strip_v2 *strip);
	void (*modes)(void *data,
		      struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
		      uint32_t modes);
	void (*done)(void *data,
		     struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2);
	void (*mode_switch)(void *data,
			    struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
			    uint32_t time,
			    uint32_t serial,
			    uint32_t mode);
	void (*dial)(void *data,
		     struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
		     struct zwp_tablet_pad_dial_v2 *dial);
};

static inline int
zwp_tablet_pad_group_v2_add_listener(struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2,
				     const struct zwp_tablet_pad_group_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_pad_group_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_PAD_GROUP_V2_DESTROY 0

#define ZWP_TABLET_PAD_GROUP_V2_BUTTONS_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_RING_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_STRIP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_MODES_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_MODE_SWITCH_SINCE_VERSION 1
#define ZWP_TABLET_PAD_GROUP_V2_DIAL_SINCE_VERSION 2

#define ZWP_TABLET_PAD_GROUP_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_group_v2_set_user_data(struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_pad_group_v2, user_data);
}

static inline void *
zwp_tablet_pad_group_v2_get_user_data(struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_pad_group_v2);
}

static inline uint32_t
zwp_tablet_pad_group_v2_get_version(struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_group_v2);
}

static inline void
zwp_tablet_pad_group_v2_destroy(struct zwp_tablet_pad_group_v2 *zwp_tablet_pad_group_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_group_v2,
			 ZWP_TABLET_PAD_GROUP_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_group_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifndef ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM
#define ZWP_TABLET_PAD_V2_BUTTON_STATE_ENUM
enum zwp_tablet_pad_v2_button_state {
	ZWP_TABLET_PAD_V2_BUTTON_STATE_RELEASED = 0,
	ZWP_TABLET_PAD_V2_BUTTON_STATE_PRESSED = 1,
};
#endif

struct zwp_tablet_pad_v2_listener {
	void (*group)(void *data,
		      struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
		      struct zwp_tablet_pad_group_v2 *pad_group);
	void (*path)(void *data,
		     struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
		     const char *path);
	void (*buttons)(void *data,
			struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
			uint32_t buttons);
	void (*done)(void *data,
		     struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2);
	void (*button)(void *data,
		       struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
		       uint32_t time,
		       uint32_t button,
		       uint32_t state);
	void (*enter)(void *data,
		      struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
		      uint32_t serial,
		      struct zwp_tablet_v2 *tablet,
		      struct wl_surface *surface);
	void (*leave)(void *data,
		      struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
		      uint32_t serial,
		      struct wl_surface *surface);
	void (*removed)(void *data,
			struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2);
};

static inline int
zwp_tablet_pad_v2_add_listener(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2,
			       const struct zwp_tablet_pad_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_pad_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_PAD_V2_SET_FEEDBACK 0
#define ZWP_TABLET_PAD_V2_DESTROY 1

#define ZWP_TABLET_PAD_V2_GROUP_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_PATH_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_BUTTONS_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_DONE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_BUTTON_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_ENTER_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_LEAVE_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_REMOVED_SINCE_VERSION 1

#define ZWP_TABLET_PAD_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_v2_set_user_data(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_pad_v2, user_data);
}

static inline void *
zwp_tablet_pad_v2_get_user_data(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_pad_v2);
}

static inline uint32_t
zwp_tablet_pad_v2_get_version(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_v2);
}

static inline void
zwp_tablet_pad_v2_set_feedback(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2, uint32_t button, const char *description, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_v2,
			 ZWP_TABLET_PAD_V2_SET_FEEDBACK, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_v2), 0, button, description, serial);
}

static inline void
zwp_tablet_pad_v2_destroy(struct zwp_tablet_pad_v2 *zwp_tablet_pad_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_v2,
			 ZWP_TABLET_PAD_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_v2), WL_MARSHAL_FLAG_DESTROY);
}

struct zwp_tablet_pad_dial_v2_listener {
	void (*delta)(void *data,
		      struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2,
		      int32_t value120);
	void (*frame)(void *data,
		      struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2,
		      uint32_t time);
};

static inline int
zwp_tablet_pad_dial_v2_add_listener(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2,
				    const struct zwp_tablet_pad_dial_v2_listener *listener, void *data)
{
	return wl_proxy_add_listener((struct wl_proxy *) zwp_tablet_pad_dial_v2,
				     (void (**)(void)) listener, data);
}

#define ZWP_TABLET_PAD_DIAL_V2_SET_FEEDBACK 0
#define ZWP_TABLET_PAD_DIAL_V2_DESTROY 1

#define ZWP_TABLET_PAD_DIAL_V2_DELTA_SINCE_VERSION 1
#define ZWP_TABLET_PAD_DIAL_V2_FRAME_SINCE_VERSION 1

#define ZWP_TABLET_PAD_DIAL_V2_SET_FEEDBACK_SINCE_VERSION 1
#define ZWP_TABLET_PAD_DIAL_V2_DESTROY_SINCE_VERSION 1

static inline void
zwp_tablet_pad_dial_v2_set_user_data(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2, void *user_data)
{
	wl_proxy_set_user_data((struct wl_proxy *) zwp_tablet_pad_dial_v2, user_data);
}

static inline void *
zwp_tablet_pad_dial_v2_get_user_data(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2)
{
	return wl_proxy_get_user_data((struct wl_proxy *) zwp_tablet_pad_dial_v2);
}

static inline uint32_t
zwp_tablet_pad_dial_v2_get_version(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2)
{
	return wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_dial_v2);
}

static inline void
zwp_tablet_pad_dial_v2_set_feedback(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2, const char *description, uint32_t serial)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_dial_v2,
			 ZWP_TABLET_PAD_DIAL_V2_SET_FEEDBACK, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_dial_v2), 0, description, serial);
}

static inline void
zwp_tablet_pad_dial_v2_destroy(struct zwp_tablet_pad_dial_v2 *zwp_tablet_pad_dial_v2)
{
	wl_proxy_marshal_flags((struct wl_proxy *) zwp_tablet_pad_dial_v2,
			 ZWP_TABLET_PAD_DIAL_V2_DESTROY, NULL, wl_proxy_get_version((struct wl_proxy *) zwp_tablet_pad_dial_v2), WL_MARSHAL_FLAG_DESTROY);
}

#ifdef  __cplusplus
}
#endif

#endif
