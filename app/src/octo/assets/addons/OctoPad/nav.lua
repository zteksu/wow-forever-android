-- Gamepad navigation of open windows, as in Forever: the D-pad moves a highlight between buttons, A clicks,
-- X is the secondary action (use or equip an item, bind a spell), Y opens a context menu, B goes back or
-- closes, LT/RT switch between open windows and the bumpers switch tabs. Pointing with the right stick
-- works too: A clicks whatever the pointer is over.
--
-- Also bind mode ("Bind to Action Bar"): the next button combination you press gets the spell or item.

local OP = OctoPad
local nav = { panel = nil, focus = nil }
OP.nav = nav

-- Windows that aren't in UIPanelWindows or UISpecialFrames: popups and menus come before the windows,
-- bags after them (a vendor opens your bags too, and focus should start on the vendor).
local POPUPS = {
  "StaticPopup1", "StaticPopup2", "StaticPopup3", "StaticPopup4",
  "DropDownList1", "DropDownList2", "DropDownList3",
  "LootFrame", "GameMenuFrame", "ItemRefTooltip",
}
local BAGS = {}
for i = 1, 12 do table.insert(BAGS, "ContainerFrame" .. i) end

-- Big full-screen frames that never need gamepad navigation.
local IGNORED = { WorldFrame = true, UIParent = true, OctoPadBar = true }

-- Visible windows in a stable order: our menu, popups, windows, then bags.
function nav:Panels()
  local seen, list = {}, {}
  local function add(name)
    if seen[name] or IGNORED[name] then return end
    seen[name] = true
    local frame = getglobal(name)
    if frame and frame.IsVisible and frame:IsVisible() then table.insert(list, frame) end
  end
  add("OctoPadMenu")
  for _, name in ipairs(POPUPS) do add(name) end
  for name in pairs(UIPanelWindows) do add(name) end
  for _, name in ipairs(UISpecialFrames) do add(name) end
  for _, name in ipairs(BAGS) do add(name) end
  return list
end

local function clickable(f)
  if not f:IsVisible() then return false end
  local kind = f:GetObjectType()
  if kind == "Slider" then return true end
  if kind ~= "Button" and kind ~= "CheckButton" then return false end
  if f.IsEnabled and not f:IsEnabled() then return false end
  if not f:GetScript("OnClick") then return false end
  local w, h = f:GetWidth(), f:GetHeight()
  return w and h and w > 4 and h > 4 and f:GetCenter() ~= nil
end

local function collect(frame, out)
  local children = { frame:GetChildren() }
  for _, child in ipairs(children) do
    if child:IsVisible() then
      if clickable(child) then table.insert(out, child) end
      collect(child, out)
    end
  end
  return out
end

function nav:Buttons(panel)
  return collect(panel, {})
end

-- Screen position, so frames with different scales compare correctly.
local function center(f)
  local x, y = f:GetCenter()
  local s = f:GetEffectiveScale()
  return x * s, y * s
end

local VECTORS = { UP = { 0, 1 }, DOWN = { 0, -1 }, LEFT = { -1, 0 }, RIGHT = { 1, 0 } }

-- The nearest button in [direction], favouring ones straight ahead.
function nav:Neighbour(buttons, from, direction)
  local fx, fy = center(from)
  local v = VECTORS[direction]
  local best, bestScore
  for _, b in ipairs(buttons) do
    if b ~= from then
      local x, y = center(b)
      local dx, dy = x - fx, y - fy
      local along = dx * v[1] + dy * v[2]
      if along > 1 then
        local across = math.abs(dx * v[2] - dy * v[1])
        local score = along + 2 * across
        if not bestScore or score < bestScore then best, bestScore = b, score end
      end
    end
  end
  return best
end

-- Top-left-most button: where focus starts in a window.
function nav:First(buttons)
  local best, bestScore
  for _, b in ipairs(buttons) do
    local x, y = center(b)
    local score = x - 2 * y
    if not bestScore or score < bestScore then best, bestScore = b, score end
  end
  return best
end

local function callScript(frame, script, button)
  local fn = frame:GetScript(script)
  if not fn then return end
  local oldThis, oldArg1 = this, arg1
  this, arg1 = frame, button
  local ok, err = pcall(fn)
  this, arg1 = oldThis, oldArg1
  if not ok then OP:Print("|cffff4040" .. tostring(err)) end
end

function nav:Click(frame, button)
  button = button or "LeftButton"
  if button == "LeftButton" and frame.Click then
    frame:Click()
    return
  end
  -- A real click toggles a check box before its OnClick runs.
  if frame:GetObjectType() == "CheckButton" then
    if frame:GetChecked() then frame:SetChecked(nil) else frame:SetChecked(1) end
  end
  callScript(frame, "OnClick", button)
end

-- ---- Highlight ----

local highlight = CreateFrame("Frame", "OctoPadNavHighlight", UIParent)
highlight:SetFrameStrata("TOOLTIP")
highlight:SetBackdrop({ edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border", edgeSize = 14 })
highlight:SetBackdropBorderColor(0.2, 0.8, 1, 1)
highlight:Hide()

function nav:SetFocus(frame)
  if self.focus == frame then return end
  if self.focus then callScript(self.focus, "OnLeave") end
  self.focus = frame
  if not frame then
    highlight:Hide()
    return
  end
  highlight:ClearAllPoints()
  highlight:SetPoint("TOPLEFT", frame, "TOPLEFT", -4, 4)
  highlight:SetPoint("BOTTOMRIGHT", frame, "BOTTOMRIGHT", 4, -4)
  highlight:Show()
  callScript(frame, "OnEnter")
end

-- ---- State ----

function nav:Refresh()
  local panels = self:Panels()
  self.panels = panels
  if table.getn(panels) == 0 then
    self.panel = nil
    self:SetFocus(nil)
    return
  end
  local stillOpen = false
  for _, p in ipairs(panels) do
    if p == self.panel then stillOpen = true end
  end
  if not stillOpen then
    self.panel = panels[1]
    self:SetFocus(nil)
  end
  if not self.focus or not self.focus:IsVisible() then
    self:SetFocus(self:First(self:Buttons(self.panel)))
  end
end

function nav:Active()
  return self.panel ~= nil and not OP.bind:Active()
end

function nav:Move(direction)
  local buttons = self:Buttons(self.panel)
  if not self.focus then
    self:SetFocus(self:First(buttons))
    return
  end
  if self.focus:GetObjectType() == "Slider" and (direction == "LEFT" or direction == "RIGHT") then
    local min, max = self.focus:GetMinMaxValues()
    local step = self.focus:GetValueStep()
    if not step or step <= 0 then step = (max - min) / 20 end
    local value = self.focus:GetValue() + (direction == "RIGHT" and step or -step)
    self.focus:SetValue(math.max(min, math.min(max, value)))
    return
  end
  local nextButton = self:Neighbour(buttons, self.focus, direction)
  if nextButton then
    self:SetFocus(nextButton)
  else
    self:Scroll(direction)
  end
end

-- Past the edge: page or scroll the window.
function nav:Scroll(direction)
  local panel = self.panel
  local name = panel:GetName() or ""
  if direction == "LEFT" or direction == "RIGHT" then
    for _, b in ipairs(self:Buttons(panel)) do
      local n = b:GetName() or ""
      local wanted = direction == "LEFT" and "Prev" or "Next"
      if string.find(n, wanted .. "PageButton$") or string.find(n, wanted .. "Button$") then
        self:Click(b)
        return
      end
    end
    return
  end
  local sliders = {}
  local function find(frame)
    local children = { frame:GetChildren() }
    for _, child in ipairs(children) do
      if child:IsVisible() then
        if child:GetObjectType() == "Slider" then table.insert(sliders, child) end
        find(child)
      end
    end
  end
  find(panel)
  local slider = sliders[1]
  if not slider then return end
  local min, max = slider:GetMinMaxValues()
  local step = slider:GetValueStep()
  if not step or step <= 0 then step = 16 end
  local value = slider:GetValue() + (direction == "DOWN" and step or -step)
  slider:SetValue(math.max(min, math.min(max, value)))
end

function nav:CyclePanel(step)
  local panels = self.panels or {}
  local count = table.getn(panels)
  if count < 2 then return end
  local current = 1
  for i, p in ipairs(panels) do
    if p == self.panel then current = i end
  end
  current = current + step
  if current > count then current = 1 elseif current < 1 then current = count end
  self.panel = panels[current]
  self:SetFocus(self:First(self:Buttons(self.panel)))
end

-- Bumpers: the window's tabs (Character, Friends, Spellbook schools, ...).
function nav:Bumper(button)
  local panel = self.panel
  local name = panel:GetName()
  if not name then return end
  local tabs = {}
  local prefix = name .. "Tab"
  if name == "SpellBookFrame" then prefix = "SpellBookSkillLineTab" end
  for i = 1, 12 do
    local tab = getglobal(prefix .. i)
    if tab and tab:IsVisible() then table.insert(tabs, { frame = tab, id = i }) end
  end
  local count = table.getn(tabs)
  if count == 0 then return end
  local selected = 1
  for i, tab in ipairs(tabs) do
    local isSelected
    if tab.frame:GetObjectType() == "CheckButton" then
      isSelected = tab.frame:GetChecked()
    else
      isSelected = panel.selectedTab == tab.id
    end
    if isSelected then selected = i end
  end
  selected = selected + (button == "RB" and 1 or -1)
  if selected > count then selected = 1 elseif selected < 1 then selected = count end
  self:Click(tabs[selected].frame)
end

-- The button under the pointer, if the right stick moved it since the D-pad did.
function nav:PointerTarget()
  local x, y = GetCursorPosition()
  local moved = self.cursor ~= nil and self.cursor ~= x .. "," .. y
  self.cursor = x .. "," .. y
  if not moved then return nil end
  local f = GetMouseFocus()
  if f and f ~= WorldFrame and clickable(f) then return f end
end

local function bagSlot(f)
  local parent = f:GetParent()
  local name = f:GetName() or ""
  if parent and string.find(name, "^ContainerFrame%d+Item%d+$") then return parent:GetID(), f:GetID() end
end

local function spellIndex(f)
  local name = f:GetName() or ""
  if string.find(name, "^SpellButton%d+$") and SpellBook_GetSpellID then
    local id = SpellBook_GetSpellID(f:GetID())
    if id and GetSpellName(id, SpellBookFrame.bookType) then return id end
  end
end

local function questIndex(f)
  local name = f:GetName() or ""
  if string.find(name, "^QuestLogTitle%d+$") then return f:GetID() end
end

-- X: the secondary action.
function nav:Secondary(f)
  local spell = spellIndex(f)
  if spell then
    OP.bind:Start({ kind = "spell", id = spell, book = SpellBookFrame.bookType, name = GetSpellName(spell, SpellBookFrame.bookType) })
    return
  end
  -- Right-click: use or equip a bag item, sell to a vendor, buy, ...
  self:Click(f, "RightButton")
end

-- Y: a context menu for what is focused.
function nav:Context(f)
  local bag, slot = bagSlot(f)
  if bag then
    local texture, count = GetContainerItemInfo(bag, slot)
    if not texture then return end
    local link = GetContainerItemLink(bag, slot) or "Item"
    local _, _, itemName = string.find(link, "%[(.+)%]")
    local items = {
      { "Bind to Action Bar", function() OP.bind:Start({ kind = "item", bag = bag, slot = slot, name = itemName or link }) end },
      { "Use / Equip", function() UseContainerItem(bag, slot) end },
    }
    if count and count > 1 then
      table.insert(items, { "Split Stack", function() SplitContainerItem(bag, slot, math.floor(count / 2)) end })
    end
    table.insert(items, { "Destroy", function()
      -- The game's own confirmation; it destroys the item on the cursor or puts it back.
      PickupContainerItem(bag, slot)
      StaticPopup_Show("DELETE_ITEM", itemName or link)
    end })
    OP.menu:Open(itemName or "Item", items, f)
    return
  end
  local spell = spellIndex(f)
  if spell then
    local book = SpellBookFrame.bookType
    OP.menu:Open(GetSpellName(spell, book), {
      { "Bind to Action Bar", function() OP.bind:Start({ kind = "spell", id = spell, book = book, name = GetSpellName(spell, book) }) end },
      { "Cast", function() CastSpell(spell, book) end },
    }, f)
    return
  end
  local quest = questIndex(f)
  if quest then
    local index = quest
    local title = GetQuestLogTitle(index)
    OP.menu:Open(title, {
      { "Track", function() if IsQuestWatched(index) then RemoveQuestWatch(index) else AddQuestWatch(index) end QuestWatch_Update() end },
      { "Share", function() SelectQuestLogEntry(index); QuestLogPushQuest() end },
      { "Abandon", function()
        SelectQuestLogEntry(index)
        SetAbandonQuest()
        StaticPopup_Show("ABANDON_QUEST", GetAbandonQuestName())
      end },
    }, f)
    return
  end
  self:Click(f, "RightButton")
end

-- B: back out of a menu, a popup, then the window itself.
function nav:Back()
  local panel = self.panel
  local name = panel:GetName() or ""
  if string.find(name, "^DropDownList") then
    CloseDropDownMenus()
  elseif string.find(name, "^StaticPopup%d$") then
    local cancel = getglobal(name .. "Button2")
    if cancel and cancel:IsVisible() then self:Click(cancel) else panel:Hide() end
  elseif string.find(name, "^ContainerFrame") then
    CloseAllBags()
  elseif UIPanelWindows[name] then
    HideUIPanel(panel)
  else
    panel:Hide()
  end
  self.focus = nil
  self:Refresh()
end

function nav:Press(button)
  if OP.DPAD[button] then
    self.cursor = nil
    self:Move(button)
    return
  end
  local target = self:PointerTarget() or self.focus
  if button == "B" then
    self:Back()
  elseif not target then
    return
  elseif button == "A" then
    if target:GetObjectType() ~= "Slider" then self:Click(target, "LeftButton") end
  elseif button == "X" then
    self:Secondary(target)
  elseif button == "Y" then
    self:Context(target)
  end
end

function nav:Trigger(button)
  self:CyclePanel(button == "RT" and 1 or -1)
end

OP:AddCapture(nav, 6)

-- Windows come and go without events we can rely on; look four times a second.
local watcher = CreateFrame("Frame", "OctoPadNavWatcher", UIParent)
watcher.elapsed = 0
watcher:SetScript("OnUpdate", function()
  this.elapsed = this.elapsed + arg1
  if this.elapsed < 0.25 then return end
  this.elapsed = 0
  nav:Refresh()
end)

OP:On("PANEL_OPENED", function(panel)
  nav:Refresh()
  nav.panel = panel
  nav:SetFocus(nav:First(nav:Buttons(panel)))
end)

-- ---- Bind mode ----

local bind = CreateFrame("Frame", "OctoPadBindPrompt", UIParent)
OP.bind = bind
bind:SetFrameStrata("FULLSCREEN_DIALOG")
bind:SetWidth(420)
bind:SetHeight(54)
bind:SetPoint("TOP", UIParent, "TOP", 0, -140)
bind:SetBackdrop({
  bgFile = "Interface\\Tooltips\\UI-Tooltip-Background",
  edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
  tile = true, tileSize = 16, edgeSize = 16,
  insets = { left = 4, right = 4, top = 4, bottom = 4 },
})
bind:SetBackdropColor(0, 0, 0, 0.9)
bind:Hide()
bind.text = bind:CreateFontString(nil, "OVERLAY", "GameFontHighlight")
bind.text:SetPoint("CENTER", bind, "CENTER", 0, 0)
bind.text:SetWidth(400)

-- payload: { kind = "spell"|"item"|"flyout"|"clear", ... , name = shown }
function bind:Start(payload)
  self.payload = payload
  local what = payload.name or "it"
  if payload.kind == "clear" then
    self.text:SetText("Press the button combination to clear.\nB alone cancels.")
  else
    self.text:SetText("Bind |cffffd100" .. what .. "|r: hold LT, RT or both and press a button,\nor press a D-pad direction. B alone cancels.")
  end
  self:Show()
end

function bind:Active()
  return self:IsVisible()
end

function bind:Press(button)
  local layer = OP:Layer()
  if layer == "BASE" and OP.FACE[button] then
    if button == "B" then
      self:Hide()
    else
      OP:Print("A, X and Y are fixed without a trigger; hold LT or RT.")
    end
    return
  end
  local arrangement = OP:Arrangement()
  local actions = OP.actions
  local slot = actions:Slot(arrangement, layer, button)
  local key = actions:Key(arrangement, layer, button)
  local p = self.payload
  ClearCursor()
  if p.kind == "flyout" then
    OctoPadCharDB.flyouts[key] = p.flyout
  else
    OctoPadCharDB.flyouts[key] = nil
    if p.kind == "spell" then
      PickupSpell(p.id, p.book)
      PlaceAction(slot)
    elseif p.kind == "item" then
      PickupContainerItem(p.bag, p.slot)
      PlaceAction(slot)
    elseif p.kind == "clear" then
      PickupAction(slot)
    end
    -- PlaceAction hands back whatever the slot held; drop it (and put an item back in its bag).
    ClearCursor()
  end
  self:Hide()
  local combo = layer == "BASE" and button or (layer == "LTRT" and "LT+RT" or layer) .. "+" .. button
  if p.kind == "clear" then
    OP:Print("Cleared " .. combo .. ".")
  else
    OP:Print("Bound " .. (p.name or "it") .. " to " .. combo .. " (bars " .. arrangement .. ").")
  end
  actions:Update()
end

OP:AddCapture(bind, 1)

-- ---- Moving the bar ----
-- Forever puts the gamepad bar in Edit Mode; here the D-pad nudges it.

local mover = CreateFrame("Frame", "OctoPadMover", UIParent)
OP.mover = mover
mover:Hide()
mover.text = mover:CreateFontString(nil, "OVERLAY", "GameFontHighlightLarge")
mover.text:SetPoint("TOP", UIParent, "TOP", 0, -180)
mover.text:SetText("Move the bar with the D-pad. A: done, Y: reset.")
mover:SetAllPoints(UIParent)

function mover:Active()
  return self:IsVisible()
end

function mover:Press(button)
  local pos = OctoPadDB.barPos or { x = 0, y = 14 }
  local step = 10
  if button == "UP" then pos.y = pos.y + step
  elseif button == "DOWN" then pos.y = pos.y - step
  elseif button == "LEFT" then pos.x = pos.x - step
  elseif button == "RIGHT" then pos.x = pos.x + step
  elseif button == "Y" then pos = nil
  elseif button == "A" or button == "B" then
    self:Hide()
    return
  end
  OctoPadDB.barPos = pos
  OP.actions:Layout()
end

OP:AddCapture(mover, 5)
