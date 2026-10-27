-- /octopad, or Gamepad Options in the radial menu: Forever's Gamepad (Alpha) settings, plus placing
-- flyouts and moving the bar. Everything here works with the D-pad.

local OP = OctoPad

local options = CreateFrame("Frame", "OctoPadOptions", UIParent)
OP.options = options
table.insert(UISpecialFrames, "OctoPadOptions")
options:SetFrameStrata("DIALOG")
options:SetWidth(380)
options:SetHeight(500)
options:SetPoint("CENTER", UIParent, "CENTER", 0, 20)
options:SetBackdrop({
  bgFile = "Interface\\DialogFrame\\UI-DialogBox-Background",
  edgeFile = "Interface\\DialogFrame\\UI-DialogBox-Border",
  tile = true, tileSize = 32, edgeSize = 32,
  insets = { left = 11, right = 12, top = 12, bottom = 11 },
})
options:EnableMouse(true)
options:Hide()

options.title = options:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
options.title:SetPoint("TOP", options, "TOP", 0, -18)
options.title:SetText("OctoPad Gamepad Options")

local CHECKS = {
  { "compact", "Use Compact Action Bar", "Only show the bar for the triggers you are holding." },
  { "fixedParty", "Fixed Party Targeting", "In a party (not a raid), LB + each D-pad direction targets one party member." },
  { "swapModifierSides", "Swap Target Modifier Sides", "Friendly targeting on RB, hostile on LB." },
  { "swapFriendlyActions", "Swap Friendly Target Action Sides", "Swap the D-pad and face button actions while friendly targeting." },
  { "swapHostileActions", "Swap Hostile Target Action Sides", "Swap the D-pad and face button actions while hostile targeting." },
  { "autoTarget", "Target Enemy Ahead On Action", "In combat, an action with no target picks the enemy in front of you." },
  { "interactPrompt", "Show Interact Prompt", "Name what X will interact with." },
  { "hideBlizzardBars", "Hide Default Action Bars", "Needs /reload to bring them back." },
}

options.checks = {}
for i, def in ipairs(CHECKS) do
  local check = CreateFrame("CheckButton", "OctoPadOptionsCheck" .. i, options, "UICheckButtonTemplate")
  check:SetPoint("TOPLEFT", options, "TOPLEFT", 22, -44 - (i - 1) * 28)
  getglobal(check:GetName() .. "Text"):SetText(def[2])
  check.setting = def[1]
  check.tooltip = def[3]
  check:SetScript("OnClick", function()
    OP:Set(this.setting, this:GetChecked() and true or false)
  end)
  check:SetScript("OnEnter", function()
    GameTooltip:SetOwner(this, "ANCHOR_RIGHT")
    GameTooltip:SetText(this.tooltip, nil, nil, nil, nil, 1)
  end)
  check:SetScript("OnLeave", function() GameTooltip:Hide() end)
  options.checks[i] = check
end

local function slider(name, label, setting, min, max, step, y, format)
  local s = CreateFrame("Slider", "OctoPadOptions" .. name, options, "OptionsSliderTemplate")
  s:SetPoint("TOPLEFT", options, "TOPLEFT", 30, y)
  s:SetWidth(320)
  s:SetMinMaxValues(min, max)
  s:SetValueStep(step)
  s.setting = setting
  s.label = label
  s.format = format
  getglobal(s:GetName() .. "Low"):SetText(string.format(format, min))
  getglobal(s:GetName() .. "High"):SetText(string.format(format, max))
  s:SetScript("OnValueChanged", function()
    getglobal(this:GetName() .. "Text"):SetText(this.label .. ": " .. string.format(this.format, this:GetValue()))
    if options.loading then return end
    OP:Set(this.setting, this:GetValue())
  end)
  return s
end

options.camera = slider("Camera", "Camera speed", "cameraSpeed", 0.3, 3, 0.1, -44 - table.getn(CHECKS) * 28 - 18, "%.1f")
options.scale = slider("Scale", "Bar size", "scale", 0.6, 1.6, 0.05, -44 - table.getn(CHECKS) * 28 - 62, "%.2f")

local function button(label, x, y, run)
  local b = CreateFrame("Button", nil, options, "UIPanelButtonTemplate")
  b:SetWidth(160)
  b:SetHeight(22)
  b:SetPoint("BOTTOMLEFT", options, "BOTTOMLEFT", x, y)
  b:SetText(label)
  b:SetScript("OnClick", run)
  return b
end

local function placeFlyout(kind)
  return function()
    options:Hide()
    OP.bind:Start({ kind = "flyout", flyout = kind, name = OP.actions.FLYOUTS[kind].title .. " flyout" })
  end
end

button("Place Stance Flyout", 22, 88, placeFlyout("stance"))
button("Place Pet Control Flyout", 198, 88, placeFlyout("petcontrol"))
button("Place Pet Spells Flyout", 22, 62, placeFlyout("petspells"))
button("Clear a Slot", 198, 62, function()
  options:Hide()
  OP.bind:Start({ kind = "clear" })
end)
button("Move Bar", 22, 36, function()
  options:Hide()
  OP.mover:Show()
end)
button("Close", 198, 36, function() options:Hide() end)

options.help = options:CreateFontString(nil, "OVERLAY", "GameFontDisableSmall")
options.help:SetPoint("BOTTOM", options, "BOTTOM", 0, 18)
options.help:SetText("Bind spells: Spellbook, X on a spell. Items: bags, Y on an item.")

function options:Refresh()
  self.loading = true
  for _, check in ipairs(self.checks) do
    check:SetChecked(OP:Setting(check.setting) and 1 or nil)
  end
  self.camera:SetValue(OP:Setting("cameraSpeed"))
  self.scale:SetValue(OP:Setting("scale"))
  self.loading = nil
end

function options:Toggle()
  if self:IsVisible() then
    self:Hide()
  else
    self:Refresh()
    self:Show()
    OP:Fire("PANEL_OPENED", self)
  end
end
