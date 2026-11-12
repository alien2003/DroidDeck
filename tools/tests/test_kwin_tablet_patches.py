from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
UBUNTU = ROOT / "tools/ubuntu/patches/kwin"
ARCH = ROOT / "tools/desktop-kde/patches"
UBUNTU_TABLET = UBUNTU / "0005-forward-nested-tablet-input.patch"
UBUNTU_CORE = [UBUNTU / "0006-frame-tablet-tool-buttons.patch", UBUNTU / "0007-route-tablet-tip-up-and-proximity.patch"]
ARCH_TABLET = ARCH / "0004-forward-nested-tablet-input.patch"
ARCH_CORE = [ARCH / "0005-frame-tablet-tool-buttons.patch", ARCH / "0006-route-tablet-tip-up-and-proximity.patch"]
CORE_FILES = {"src/input.cpp", "src/tablet_input.cpp", "src/tablet_input.h"}


def files(patch):
    return {}.fromkeys(re.findall(r"^diff --git a/(\S+) b/", patch.read_text(), re.M)).keys()


def section(patch, name):
    text = patch.read_text()
    begin = text.index(f"diff --git a/{name} b/{name}")
    end = text.find("\ndiff --git ", begin + 1)
    return text[begin:end if end != -1 else len(text)]


def lines(patch, sign, name=None):
    text = section(patch, name) if name else patch.read_text()
    marker = sign * 3
    return [line[1:] for line in text.splitlines() if line.startswith(sign) and not line.startswith(marker)]


def added(patch, name=None):
    return "\n".join(lines(patch, "+", name)) + "\n"


def block(text, start, end="\n}\n"):
    begin = text.index(start)
    return text[begin:text.index(end, begin) + len(end)]


def pure_parts(patch):
    text = added(patch, "src/backends/wayland/wayland_tablet.cpp")
    return [
        block(text, "struct WaylandTabletToolFrame\n", "\n};\n"),
        block(text, "struct WaylandTabletToolActions\n", "\n};\n"),
        block(text, "static WaylandTabletToolActions planTabletToolFrame("),
        block(text, "static QPointF tabletTiltToOutput("),
        block(text, "static qreal tabletRotationToOutput("),
    ]


PREAMBLE = r'''
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <utility>
#include <vector>

using qreal = double;
using quint32 = uint32_t;

struct QPointF
{
    qreal xp = 0;
    qreal yp = 0;
    QPointF() = default;
    QPointF(qreal x, qreal y) : xp(x), yp(y) {}
    qreal x() const { return xp; }
    qreal y() const { return yp; }
};

inline QPointF operator+(QPointF a, QPointF b) { return QPointF(a.x() + b.x(), a.y() + b.y()); }
inline QPointF operator-(QPointF a, QPointF b) { return QPointF(a.x() - b.x(), a.y() - b.y()); }

// Qt's default QSizeF is invalid, (-1, -1).
struct QSizeF
{
    qreal w = -1;
    qreal h = -1;
    QSizeF() = default;
    QSizeF(qreal width, qreal height) : w(width), h(height) {}
    qreal width() const { return w; }
    qreal height() const { return h; }
};

template<typename T>
struct QList : std::vector<T>
{
    using std::vector<T>::vector;
    void append(const T &value) { this->push_back(value); }
    bool contains(const T &value) const { return std::find(this->begin(), this->end(), value) != this->end(); }
    bool removeOne(const T &value)
    {
        auto it = std::find(this->begin(), this->end(), value);
        if (it == this->end()) {
            return false;
        }
        this->erase(it);
        return true;
    }
};

inline qreal qDegreesToRadians(qreal degrees) { return degrees * M_PI / 180; }
inline qreal qRadiansToDegrees(qreal radians) { return radians * 180 / M_PI; }

// KWin's OutputTransform (src/core/output.cpp): the map from logical to device coordinates.
class OutputTransform
{
public:
    enum Kind { Normal, Rotate90, Rotate180, Rotate270, FlipX, FlipX90, FlipX180, FlipX270 };
    OutputTransform(Kind kind) : m_kind(kind) {}
    OutputTransform inverted() const
    {
        return m_kind == Rotate90 ? Rotate270 : m_kind == Rotate270 ? Rotate90 : m_kind;
    }
    QPointF map(const QPointF &p, const QSizeF &b) const
    {
        switch (m_kind) {
        case Normal: return p;
        case Rotate90: return QPointF(p.y(), b.width() - p.x());
        case Rotate180: return QPointF(b.width() - p.x(), b.height() - p.y());
        case Rotate270: return QPointF(b.height() - p.y(), p.x());
        case FlipX: return QPointF(b.width() - p.x(), p.y());
        case FlipX90: return QPointF(p.y(), p.x());
        case FlipX180: return QPointF(p.x(), b.height() - p.y());
        case FlipX270: return QPointF(b.height() - p.y(), b.width() - p.x());
        }
        return p;
    }

private:
    Kind m_kind;
};
'''

MAIN = r'''
using P = WaylandTabletToolFrame::Proximity;
using Buttons = std::vector<std::pair<quint32, bool>>;

static WaylandTabletToolActions plan(P proximity, bool down, bool up, bool axes, Buttons buttons, bool inProximity, bool tipDown, QList<quint32> held)
{
    WaylandTabletToolFrame frame;
    frame.proximity = proximity;
    frame.down = down;
    frame.up = up;
    frame.axesChanged = axes;
    frame.buttons = buttons;
    return planTabletToolFrame(frame, inProximity, tipDown, held);
}

static bool same(qreal a, qreal b) { return std::fabs(a - b) < 1e-9; }
static bool sameAngle(qreal a, qreal b) { const qreal d = std::fmod(std::fabs(a - b), 360.0); return d < 1e-9 || 360 - d < 1e-9; }

int main()
{
    {
        // a proximity in carries the axes, as in libinput
        auto a = plan(P::In, false, false, true, {}, false, false, {});
        assert(a.proximityIn && !a.axis && !a.tipDown && !a.proximityOut);
    }
    {
        auto a = plan(P::In, true, false, true, {}, false, false, {});
        assert(a.proximityIn && a.tipDown && !a.axis);
    }
    {
        auto a = plan(P::Unchanged, true, true, true, {}, true, false, {});
        assert(a.tipDown && a.tipUp && !a.axis);
    }
    {
        auto a = plan(P::Unchanged, false, false, true, {}, true, true, {});
        assert(a.axis && !a.tipDown && !a.tipUp);
    }
    {
        // an up without a down changes nothing
        auto a = plan(P::Unchanged, false, true, true, {}, true, false, {});
        assert(!a.tipUp && a.axis);
    }
    {
        // leaving proximity lifts the tip and releases every held button first
        auto a = plan(P::Out, false, false, false, {}, true, true, {331});
        assert(a.tipUp && a.proximityOut && a.buttons == Buttons({{331, false}}));
    }
    {
        auto a = plan(P::Out, false, false, false, {{332, true}}, true, false, {331});
        assert(a.proximityOut && a.buttons == Buttons({{332, true}, {331, false}, {332, false}}));
    }
    {
        // out and in within one frame is a move to another surface
        auto a = plan(P::In, false, false, true, {}, true, false, {});
        assert(!a.proximityIn && !a.proximityOut && a.axis);
    }
    {
        // in and out within one frame never reaches KWin
        auto a = plan(P::Out, true, false, true, {{331, true}}, false, false, {});
        assert(!a.proximityIn && !a.proximityOut && !a.axis && !a.tipDown && a.buttons.empty());
    }
    {
        auto a = plan(P::Unchanged, true, false, true, {{331, true}}, false, false, {});
        assert(!a.axis && !a.tipDown && a.buttons.empty());
    }
    {
        auto a = plan(P::Unchanged, false, false, false, {{331, true}, {331, true}, {332, false}}, true, false, {});
        assert(a.buttons == Buttons({{331, true}}));
    }
    {
        auto a = plan(P::Unchanged, false, false, false, {{331, true}, {331, false}}, true, false, {});
        assert(a.buttons == Buttons({{331, true}, {331, false}}));
    }

    const OutputTransform::Kind kinds[] = {OutputTransform::Normal, OutputTransform::Rotate90, OutputTransform::Rotate180, OutputTransform::Rotate270,
                                           OutputTransform::FlipX, OutputTransform::FlipX90, OutputTransform::FlipX180, OutputTransform::FlipX270};
    const QSizeF panel(1280, 720);
    for (OutputTransform::Kind kind : kinds) {
        const OutputTransform transform(kind);
        // tilt turns like a position moves (WaylandOutput::mapToOutput), without the offset
        for (QPointF v : {QPointF(1, 0), QPointF(0, 1), QPointF(30, -20)}) {
            const QPointF p(200, 100);
            const QPointF moved = transform.inverted().map(p + v, panel) - transform.inverted().map(p, panel);
            const QPointF tilt = tabletTiltToOutput(transform, v);
            assert(same(tilt.x(), moved.x()) && same(tilt.y(), moved.y()));
        }
        for (qreal degrees : {0.0, 10.0, 90.0, 135.0, 200.0, 359.5}) {
            const qreal rotation = tabletRotationToOutput(transform, degrees);
            assert(rotation >= 0 && rotation < 360);
            const qreal r = qDegreesToRadians(degrees), out = qDegreesToRadians(rotation);
            const QPointF direction = tabletTiltToOutput(transform, QPointF(std::sin(r), -std::cos(r)));
            assert(same(direction.x(), std::sin(out)) && same(direction.y(), -std::cos(out)));
        }
    }

    // What clients of the patched KWin 6.6.6 received on outputs turned with kscreen-doctor.
    struct Row {
        OutputTransform::Kind kind;
        QPointF tiltX;
        QPointF tiltY;
        qreal up;
        qreal right;
    };
    const Row rows[] = {
        {OutputTransform::Normal, QPointF(30, 0), QPointF(0, 30), 0, 90},
        {OutputTransform::Rotate90, QPointF(0, 30), QPointF(-30, 0), 90, 180},
        {OutputTransform::Rotate180, QPointF(-30, 0), QPointF(0, -30), 180, 270},
        {OutputTransform::Rotate270, QPointF(0, -30), QPointF(30, 0), 270, 0},
        {OutputTransform::FlipX, QPointF(-30, 0), QPointF(0, 30), 0, 270},
        {OutputTransform::FlipX90, QPointF(0, 30), QPointF(30, 0), 270, 180},
        {OutputTransform::FlipX180, QPointF(30, 0), QPointF(0, -30), 180, 90},
        {OutputTransform::FlipX270, QPointF(0, -30), QPointF(-30, 0), 90, 0},
    };
    for (const Row &row : rows) {
        const QPointF x = tabletTiltToOutput(row.kind, QPointF(30, 0)), y = tabletTiltToOutput(row.kind, QPointF(0, 30));
        assert(same(x.x(), row.tiltX.x()) && same(x.y(), row.tiltX.y()));
        assert(same(y.x(), row.tiltY.x()) && same(y.y(), row.tiltY.y()));
        assert(sameAngle(tabletRotationToOutput(row.kind, 0), row.up));
        assert(sameAngle(tabletRotationToOutput(row.kind, 90), row.right));
    }

    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class TabletLogicTest(unittest.TestCase):
    def test_frames_reach_kwin_in_libinput_order_and_tilt_turns_with_the_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "tablet.cpp"
            binary = Path(tmp) / "tablet"
            source.write_text(PREAMBLE + "\n".join(pure_parts(UBUNTU_TABLET)) + MAIN)
            subprocess.run(["c++", "-std=c++20", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)], check=True)
            self.assertEqual(subprocess.run([str(binary)], capture_output=True, text=True, check=True).stdout.strip(), "ok")


class TabletPatchTest(unittest.TestCase):
    def test_ubuntu_patches_have_the_series_header(self):
        for patch in [UBUNTU_TABLET] + UBUNTU_CORE:
            head = patch.read_text().splitlines()
            self.assertEqual(head[0], "From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001", patch.name)
            self.assertEqual(head[1], "From: alien2003 <11610652+alien2003@users.noreply.github.com>", patch.name)
            self.assertTrue(head[2].startswith("Date: ") and head[3].startswith("Subject: [PATCH] "), patch.name)
            self.assertEqual(head[4:6], ["", "---"], patch.name)
            stat = head[6:head.index("", 6)]
            self.assertEqual([line.split("|")[0].strip() for line in stat if "|" in line], list(files(patch)), patch.name)

    def test_the_tablet_patch_stays_in_the_nested_backend(self):
        for patch in [UBUNTU_TABLET, ARCH_TABLET]:
            self.assertTrue(all(name.startswith("src/backends/wayland/") for name in files(patch)), patch.name)
        self.assertEqual(lines(UBUNTU_TABLET, "+", "src/backends/wayland/wayland_backend.h"), [
            "class WaylandTabletSeat;",
            "    WaylandTabletSeat *tabletSeat() const",
            "    {",
            "        return m_tabletSeat.get();",
            "    }",
            "    bool hasTabletToolInProximity() const;",
            "    std::unique_ptr<WaylandTabletSeat> m_tabletSeat;",
        ])
        self.assertIn("    std::unique_ptr<WaylandInputDevice> m_touchDevice;\n+    std::unique_ptr<WaylandTabletSeat> m_tabletSeat;",
                      section(UBUNTU_TABLET, "src/backends/wayland/wayland_backend.h"))

    def test_only_the_gate_binds_the_host_tablet(self):
        for patch in [UBUNTU_TABLET, ARCH_TABLET]:
            display = added(patch, "src/backends/wayland/wayland_display.cpp")
            self.assertIn('static const bool forwardTablets = qEnvironmentVariableIntValue("DROIDDECK_NESTED_TABLET") == 1;', display)
            self.assertIn("if (forwardTablets) {\n            display->m_tabletManager = ", display)
            self.assertEqual(display.count("wl_registry_bind("), 1)

    def test_every_proximity_in_hides_the_host_cursor_and_pads_are_dropped(self):
        frame = block(added(UBUNTU_TABLET, "src/backends/wayland/wayland_tablet.cpp"), "void WaylandTabletSeat::toolFrame(")
        entered = block(frame, "    if (frame.proximity == WaylandTabletToolFrame::Proximity::In) {", "\n    }\n")
        self.assertIn("zwp_tablet_tool_v2_set_cursor(tool->proxy, enterSerial, nullptr, 0, 0);", entered)
        self.assertIn("zwp_tablet_pad_v2_destroy(pad);", added(UBUNTU_TABLET))

    def test_tool_events_are_emitted_in_planned_order(self):
        frame = block(added(UBUNTU_TABLET, "src/backends/wayland/wayland_tablet.cpp"), "void WaylandTabletSeat::toolFrame(")
        steps = ["if (actions.proximityIn) {\n        Q_EMIT", "if (actions.tipDown)", "if (actions.tipUp)", "if (actions.axis)",
                 "for (const auto &[button, pressed] : actions.buttons)", "if (actions.proximityOut)"]
        positions = [frame.index(step) for step in steps]
        self.assertEqual(positions, sorted(positions))

    def test_the_software_cursor_follows_a_tool_in_proximity(self):
        for patch in [UBUNTU_TABLET, ARCH_TABLET]:
            output = added(patch, "src/backends/wayland/wayland_output.cpp")
            self.assertEqual(output.count("const bool softwareCursor = m_hasPointerLock || (m_backend->seat() && m_backend->seat()->hasTabletToolInProximity());"), 2)

    def test_both_series_carry_the_same_tablet_code(self):
        self.assertEqual(pure_parts(UBUNTU_TABLET), pure_parts(ARCH_TABLET))
        self.assertEqual(added(UBUNTU_TABLET, "src/backends/wayland/wayland_tablet.h"), added(ARCH_TABLET, "src/backends/wayland/wayland_tablet.h"))
        ubuntu = added(UBUNTU_TABLET, "src/backends/wayland/wayland_tablet.cpp").replace("    // KWin 6.6 takes the position of a proximity out as the tool's position.\n", "")
        self.assertEqual(ubuntu, added(ARCH_TABLET, "src/backends/wayland/wayland_tablet.cpp"))

    def test_the_core_fixes_stay_in_kwin_input_code_and_match_across_series(self):
        for ubuntu, arch in zip(UBUNTU_CORE, ARCH_CORE):
            self.assertLessEqual(set(files(ubuntu)), CORE_FILES, ubuntu.name)
            self.assertLessEqual(set(files(arch)), CORE_FILES, arch.name)
            for name in ["src/input.cpp", "src/tablet_input.h"]:
                if name in files(ubuntu):
                    self.assertEqual(lines(ubuntu, "+", name), lines(arch, "+", name), name)
                    self.assertEqual(lines(ubuntu, "-", name), lines(arch, "-", name), name)
        self.assertEqual(lines(UBUNTU_CORE[1], "+", "src/tablet_input.cpp"), lines(ARCH_CORE[1], "+", "src/tablet_input.cpp"))

    def test_a_tool_button_ends_with_a_frame(self):
        self.assertIn("tool->sendButton(event->button, event->pressed);\n+        // Clients apply a tool's buttons on frame.\n"
                      "+        tool->sendFrame(std::chrono::duration_cast<std::chrono::milliseconds>(event->time).count());",
                      UBUNTU_CORE[0].read_text())

    def test_the_tip_release_reaches_the_window_that_got_the_press(self):
        for patch in [UBUNTU_CORE[1], ARCH_CORE[1]]:
            tablet = section(patch, "src/tablet_input.cpp")
            self.assertIn("-    if (!tipDown) {\n-        m_tipDown = false;\n-    }\n-\n-    update();\n", tablet)
            self.assertIn("+    } else {\n+        // The focus moves only after the window that got the press has had the release.\n+        update();\n", tablet)
            self.assertIn("+    if (focusNow && focusNow->surface()) {\n+        return;\n+    }\n", tablet)
            self.assertIn("+        tool->sendProximityOut();\n+        tool->sendFrame(0);\n", tablet)
            self.assertEqual(added(patch, "src/input.cpp").count("tool->sendProximityIn(tablet);"), 2)


class TabletPackagingTest(unittest.TestCase):
    def test_the_arch_build_applies_and_records_every_patch(self):
        script = (ROOT / "tools/desktop-kde/build-kwin.sh").read_text()
        patches = sorted(path.name for path in ARCH.glob("*.patch"))
        applied = re.findall(r'patch -d "\$work/root/build/kwin-\$version" -p1 < "\$here/patches/([^"]+)"', script)
        self.assertEqual(applied, patches)
        for name in patches:
            self.assertIn(f'sha256sum "$here/patches/{name}"', script)
        self.assertIn('grep -aq DROIDDECK_NESTED_TABLET "$out/libkwin.so.$version"', script)

    def test_the_ubuntu_build_names_the_tablet_changes(self):
        script = (ROOT / "tools/ubuntu/build-debs.sh").read_text()
        self.assertIn('for p in "$here"/patches/kwin/*.patch; do patch -p1 --forward < "$p"; done', script)
        self.assertRegex(script, r'dch -l \+droiddeck -D resolute "[^"]*tablet input[^"]*"')

    def test_the_desktop_turns_the_tablet_and_its_pointer_fallback_on_by_default(self):
        script = (ROOT / "tools/linuxfs/desktop/droiddeck-desktop").read_text()
        self.assertIn("export DROIDDECK_NESTED_SCALE=1\n"
                      "export DROIDDECK_NESTED_TABLET=${DROIDDECK_NESTED_TABLET:-1}\n"
                      "export KWIN_WAYLAND_EMULATE_TABLET=${KWIN_WAYLAND_EMULATE_TABLET:-1}\n", script)


if __name__ == "__main__":
    unittest.main()
