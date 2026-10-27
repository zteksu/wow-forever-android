#!/usr/bin/env python3
"""Drives the OctoPad addon through a mock 1.12 API (Lua 5.1 via lupa): button presses in, API calls out.

Usage: python3 tools/octopad_test/test_octopad.py
"""
import unittest
from pathlib import Path

from lupa import lua51

HERE = Path(__file__).resolve().parent
ADDON = HERE.parents[1] / "app/src/octo/assets/addons/OctoPad"


def load_addon():
    lua = lua51.LuaRuntime(unpack_returned_tuples=True)
    lua.execute((HERE / "wowmock.lua").read_text())
    for line in (ADDON / "OctoPad.toc").read_text().splitlines():
        line = line.strip()
        if line.endswith(".lua"):
            chunk = lua.globals().loadstring((ADDON / line).read_text(), "@" + line)
            if not callable(chunk):
                raise SyntaxError(f"{line} does not compile")
            chunk()
    lua.execute('FireEvent("VARIABLES_LOADED"); FireEvent("PLAYER_ENTERING_WORLD"); Reset()')
    return lua


class OctoPadTest(unittest.TestCase):
    def setUp(self):
        self.lua = load_addon()
        self.g = self.lua.globals()

    def run_lua(self, code):
        return self.lua.execute(code)

    def calls(self):
        return list(self.g.CALLS.values())

    def assertCalled(self, call):
        self.assertIn(call, self.calls())

    def assertNotCalledPrefix(self, prefix):
        self.assertFalse(any(c.startswith(prefix) for c in self.calls()), self.calls())

    def test_bindings_cover_every_button(self):
        lua = load_addon()
        lua.execute('Reset(); FireEvent("PLAYER_ENTERING_WORLD")')
        calls = list(lua.globals().CALLS.values())
        for key, name in [("F1", "A"), ("F12", "RT"), ("NUMPADMINUS", "START"), ("NUMPADDIVIDE", "L3")]:
            self.assertIn(f"SetBinding({key},OCTOPAD_{name})", calls)
        self.assertIn("SetBinding(A,TURNLEFT)", calls)

    def test_unmodified_face_buttons_are_fixed(self):
        self.run_lua('Tap("A")')
        self.assertCalled("Jump()")
        self.run_lua('STATE.target = "enemy"; Reset(); Tap("X")')
        self.assertCalled("AttackTarget()")
        self.run_lua('Reset(); Tap("B")')
        self.assertCalled("ClearTarget()")
        self.run_lua('Reset(); Tap("Y")')
        self.assertTrue(any(c.startswith("ToggleDropDownMenu(1,nil,") for c in self.calls()))

    def test_trigger_layers_pick_their_slots(self):
        self.run_lua("for i = 1, 120 do STATE.actions[i] = 'icon' end")
        cases = [
            ('Press("LT"); Tap("A"); Release("LT")', "UseAction(1,0)"),
            ('Press("RT"); Tap("UP"); Release("RT")', "UseAction(17,0)"),
            ('Press("LT"); Press("RT"); Tap("X"); Release("RT"); Release("LT")', "UseAction(23,0)"),
            ('Tap("UP")', "UseAction(9,0)"),
            ('Tap("LEFT")', "UseAction(12,0)"),
        ]
        for code, expected in cases:
            self.run_lua("Reset(); " + code)
            self.assertCalled(expected)

    def test_stances_page_bar_one(self):
        self.run_lua("for i = 1, 120 do STATE.actions[i] = 'icon' end; STATE.bonus = 1")
        self.run_lua('Reset(); Tap("UP")')
        self.assertCalled("UseAction(81,0)")
        self.run_lua('Reset(); Press("LT"); Tap("A"); Release("LT")')
        self.assertCalled("UseAction(73,0)")
        # Other layers don't page.
        self.run_lua('Reset(); Press("RT"); Tap("A"); Release("RT")')
        self.assertCalled("UseAction(13,0)")

    def test_arrangements_switch_with_lb_rb_dpad(self):
        self.run_lua("for i = 1, 120 do STATE.actions[i] = 'icon' end")
        self.run_lua('Press("LB"); Press("RB"); Tap("RIGHT"); Release("RB"); Release("LB")')
        self.run_lua('Reset(); Press("LT"); Tap("A"); Release("LT")')
        self.assertCalled("UseAction(29,0)")
        self.run_lua('Press("LB"); Press("RB"); Tap("RIGHT"); Release("RB"); Release("LB")')
        self.run_lua('Reset(); Press("LT"); Press("RT"); Tap("A"); Release("RT"); Release("LT")')
        self.assertCalled("UseAction(109,0)")
        # The shared D-pad stays the same across arrangements.
        self.run_lua('Reset(); Tap("DOWN")')
        self.assertCalled("UseAction(11,0)")
        # And a chord is not a bumper tap.
        self.assertNotCalledPrefix("TargetNearest")

    def test_bumper_taps_target(self):
        self.run_lua('Reset(); Tap("RB")')
        self.assertCalled("TargetNearestEnemy()")
        self.run_lua('Reset(); Tap("LB")')
        self.assertCalled("TargetNearestFriend()")
        # A held bumper is a modifier, not a tap.
        self.run_lua('Reset(); Press("RB"); NOW = NOW + 1; Release("RB")')
        self.assertNotCalledPrefix("TargetNearest")

    def test_unitxp_targeting_when_the_dll_is_loaded(self):
        self.run_lua('STATE.sp3 = true; OctoPad.targeting.sp3 = nil; Reset(); Tap("RB")')
        self.assertCalled("UnitXP(target,nearestEnemy)")
        self.run_lua('STATE.target = "enemy"; Reset(); Tap("RB")')
        self.assertCalled("UnitXP(target,nextEnemyConsideringDistance)")

    def test_friendly_and_hostile_modifiers(self):
        self.run_lua('Reset(); Press("LB"); Tap("A"); Release("LB")')
        self.assertCalled("TargetUnit(player)")
        self.assertNotCalledPrefix("TargetNearestFriend")
        self.run_lua('STATE.target = "enemy"; Reset(); Press("RB"); Tap("B"); Release("RB")')
        self.assertCalled("ClearTarget()")
        self.run_lua('Reset(); Press("RB"); Tap("DOWN"); Release("RB")')
        self.assertCalled("TargetLastEnemy()")
        # Swapped sides: RB is friendly.
        self.run_lua('OctoPad:Set("swapModifierSides", true); Reset(); Press("RB"); Tap("A"); Release("RB")')
        self.assertCalled("TargetUnit(player)")

    def test_fixed_party_targeting_and_swapped_action_sides(self):
        self.run_lua('STATE.party = 4; OctoPad:Set("fixedParty", true)')
        self.run_lua('Reset(); Press("LB"); Tap("RIGHT"); Release("LB")')
        self.assertCalled("TargetUnit(party2)")
        self.run_lua('OctoPad:Set("swapFriendlyActions", true); Reset(); Press("LB"); Tap("Y"); Release("LB")')
        self.assertCalled("TargetUnit(party1)")

    def test_stance_flyout(self):
        self.run_lua("""
          STATE.forms = { { texture = 't1', name = 'Battle' }, { texture = 't2', name = 'Defensive', active = 1 },
                          { texture = 't3', name = 'Berserker' } }
          OctoPadCharDB.defaults = nil
          FireEvent("UPDATE_SHAPESHIFT_FORMS")
        """)
        self.assertEqual(self.g.OctoPadCharDB.flyouts["0:BASE:LEFT"], "stance")
        self.run_lua('Reset(); Tap("LEFT")')
        self.assertTrue(self.lua.eval("OctoPadFlyout:IsVisible() == 1"))
        self.run_lua('Tap("X")')
        self.assertCalled("CastShapeshiftForm(2)")
        self.assertFalse(self.lua.eval("OctoPadFlyout:IsVisible() == 1"))
        self.assertNotCalledPrefix("UseAction")

    def test_radial_menu(self):
        self.run_lua('Reset(); Tap("START")')
        self.assertTrue(self.lua.eval("OctoPadRadial:IsVisible() == 1"))
        self.run_lua("Update(0.3)")
        self.assertIn("pointer=1", self.calls()[-1])
        self.run_lua('Tap("RIGHT"); Tap("A")')
        self.assertCalled("ToggleSpellBook(spell)")
        self.assertFalse(self.lua.eval("OctoPadRadial:IsVisible() == 1"))
        # Tabs on the bumpers, emotes on D-pad up.
        self.run_lua('Tap("START"); Tap("RB")')
        self.assertEqual(self.lua.eval("OctoPadRadial.tab"), "SOCIAL")
        self.run_lua('Tap("UP"); Reset(); Tap("A")')
        self.assertCalled("DoEmote(WAVE)")
        self.run_lua("Update(0.3)")
        self.assertIn("pointer=0", self.calls()[-1])

    def test_menu_navigation(self):
        self.run_lua("""
          local panel = CreateFrame("Frame", "GossipFrame", UIParent)
          UIPanelWindows["GossipFrame"] = {}
          local function option(name, y)
            local b = CreateFrame("Button", name, panel)
            b:SetPos(200, y)
            b:SetScript("OnClick", function() table.insert(CALLS, "Clicked(" .. this:GetName() .. ")") end)
          end
          option("GossipTitleButton1", 500)
          option("GossipTitleButton2", 470)
          Update(0.3)
          Reset()
        """)
        self.assertTrue(self.lua.eval("OctoPad.nav:Active()"))
        self.run_lua('Tap("DOWN"); Tap("A")')
        self.assertCalled("Clicked(GossipTitleButton2)")
        self.assertNotCalledPrefix("Jump")
        self.run_lua('Reset(); Tap("B")')
        self.assertTrue(any(c.startswith("HideUIPanel(") for c in self.calls()))

    def test_bind_mode_places_a_spell(self):
        self.run_lua("""OctoPad.bind:Start({ kind = "spell", id = 7, book = "spell", name = "Heroic Strike" }); Reset()""")
        self.run_lua('Tap("A")')  # fixed without a trigger
        self.assertNotCalledPrefix("PlaceAction")
        self.run_lua('Press("RT"); Tap("Y"); Release("RT")')
        self.assertCalled("PickupSpell(7,spell)")
        self.assertCalled("PlaceAction(16)")
        self.assertFalse(self.lua.eval("OctoPadBindPrompt:IsVisible() == 1"))

    def test_bridge_reports_ground_targeting_and_keyboard(self):
        self.run_lua("Reset(); STATE.spellTargeting = true; Update(0.3)")
        self.assertIn("WriteFile(OctoPad.txt,w,pointer=2", self.calls()[-1])
        self.run_lua('STATE.spellTargeting = false; Reset(); Press("LB"); Press("RB"); Tap("DOWN")')
        self.assertCalled("ChatFrame_OpenChat()")
        self.assertTrue(any("keyboard=1" in c for c in self.calls()))

    def test_autotarget_in_combat(self):
        self.run_lua("for i = 1, 120 do STATE.actions[i] = 'icon' end; STATE.combat = true")
        self.run_lua('Reset(); Press("LT"); Tap("B"); Release("LT")')
        calls = self.calls()
        self.assertLess(calls.index("TargetNearestEnemy()"), calls.index("UseAction(2,0)"))

    def test_empty_slot_does_nothing(self):
        self.run_lua('Reset(); Press("LT"); Tap("A"); Release("LT")')
        self.assertNotCalledPrefix("UseAction")
        self.assertFalse(any("ff4040" in c for c in self.calls()), self.calls())

    def test_stick_clicks_and_look_behind(self):
        self.run_lua('Reset(); Tap("L3")')
        self.assertCalled("ToggleAutoRun()")
        self.run_lua('Reset(); Tap("R3")')
        self.assertCalled("PingLocation(0,0)")
        self.run_lua('Reset(); Press("LB"); Press("RB"); Press("R3"); NOW = NOW + 1; Release("R3"); Release("RB"); Release("LB")')
        self.assertEqual([c for c in self.calls() if c.startswith("FlipCameraYaw")], ["FlipCameraYaw(180)"] * 2)
        self.assertNotCalledPrefix("PingLocation")

    def test_options_panel_with_the_dpad(self):
        self.run_lua("OctoPad.options:Toggle(); Update(0.3); Reset()")
        self.assertEqual(self.lua.eval("OctoPad.nav.panel:GetName()"), "OctoPadOptions")
        # Laid out top to bottom, so the first check box is focused; A toggles it.
        self.run_lua("for i, c in ipairs(OctoPad.options.checks) do c:SetPos(50, 600 - i * 28) end")
        self.run_lua("OctoPad.nav:SetFocus(OctoPad.options.checks[1]); Tap('A')")
        self.assertTrue(self.lua.eval("OctoPad:Setting('compact')"))
        self.run_lua('Tap("B")')
        self.assertFalse(self.lua.eval("OctoPadOptions:IsVisible() == 1"))

    def test_buff_viewer(self):
        self.run_lua("""
          function GetPlayerBuff(i) if i < 2 then return i * 3 end return -1 end
          stub("GetPlayerBuffTexture", "icon")
          stub("GetPlayerBuffTimeLeft", 75)
          OctoPadScanTooltipTextLeft1:SetText("Power Word: Fortitude")
          Reset(); Press("LB"); Press("RB"); Tap("Y"); Release("RB"); Release("LB")
        """)
        self.assertTrue(self.lua.eval("OctoPadBuffs:IsVisible() == 1"))
        self.assertEqual(self.lua.eval("OctoPadBuff1.text:GetText()"), "Power Word: Fortitude  |cffaaaaaa1:15|r")
        self.assertFalse(self.lua.eval("OctoPadBuff3:IsVisible() == 1"))
        self.run_lua("Update(0.3); OctoPad.nav:SetFocus(OctoPadBuff2); Tap('A')")
        self.assertCalled("CancelPlayerBuff(3)")


if __name__ == "__main__":
    unittest.main()
