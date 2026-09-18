--[[
    Clicker - extra clicks while the attack button is held.

    The click is queued on Minecraft's own binding rather than sent as a packet,
    which is what cold.action.click does: an interaction the payload sends
    itself arrives after that tick's movement packet, and the ordering is the
    first thing an anticheat looks at. See knowledgebase/grim-rotation-ordering.

    It also demonstrates a timer, which measures seconds off the same clock the
    animations run on.
]]

cold.script {
    name = "Clicker",
    version = "1.0",
    author = "cobble.vip",
    description = "Clicks while the button is held",
}

local clicker

clicker = cold.module {
    name = "ScriptClicker",
    description = "Extra clicks while the attack button is held",
    category = "combat",
    icon = "activity",
    settings = {
        { name = "Min CPS", type = "number", value = 8, min = 1, max = 20, decimals = 0 },
        { name = "Max CPS", type = "number", value = 12, min = 1, max = 20, decimals = 0 },
        { name = "Charged only", type = "boolean", value = true,
          description = "Wait for the attack cooldown before each click" },
    },
}

local timer = cold.timer()
local delay = 0.1

local function nextDelay()
    local settings = clicker.settings
    local low = math.min(settings["Min CPS"], settings["Max CPS"])
    local high = math.max(settings["Min CPS"], settings["Max CPS"])
    -- Sampled per click rather than held flat: a constant interval is the one
    -- thing a click pattern is never allowed to be.
    return 1.0 / (low + math.random() * (high - low))
end

function clicker.onEnable()
    timer:reset()
    delay = nextDelay()
end

function clicker.suffix()
    return string.format("%.0f-%.0f", clicker.settings["Min CPS"], clicker.settings["Max CPS"])
end

function clicker.onTick()
    if not cold.player.valid or cold.client.screenOpen then return end
    if not cold.input.attackHeld() then return end
    if clicker.settings["Charged only"] and cold.player.cooldown < 0.99 then return end

    if timer:passed(delay) then
        timer:reset()
        delay = nextDelay()
        cold.action.click(false)
    end
end
