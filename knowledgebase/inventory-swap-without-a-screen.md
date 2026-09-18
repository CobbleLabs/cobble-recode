# Moving an item without opening anything

The offhand totem swap is one call:

```
interactionManager.clickSlot(syncId, slot, 40, SlotActionType.SWAP, player)
```

- **`syncId` comes from `player.currentScreenHandler.syncId`.** The player
  screen handler is the current one whether or not a screen is showing, so
  nothing has to be opened. It is 0 when no screen is up.
- **Button 40 is the offhand.** `ScreenHandler.internalOnSlotClick` accepts
  `SWAP` only when the button is 0..8 or exactly 40; anything else is dropped
  without a word.
- **Slot numbering is the handler's, not the inventory's.** They agree on
  9..35 and disagree on the hotbar: inventory 0..8 is handler 36..44. Getting
  this wrong moves the wrong stack rather than failing.

Guard it on no screen being open. A chest or a crafting table is a different
handler and the same slot numbers point somewhere else entirely inside it.

## The version that moved the real cursor

An earlier InvTotem opened the actual inventory with a synthetic E, walked the
GLFW pointer to the slot along a bowed quadratic so the hovered-slot highlight
followed it, and pressed a synthetic F. Every packet it produced was one the
game built from input the window really received, which was the point.

It is gone. In a fight it is a menu in your face for a fifth of a second, and
the thing it was buying - a spectator seeing a pointer move - is not worth
that. The geometry, if it is ever wanted again: the survival inventory is a
fixed 176x166 panel centred in the scaled window, slots sit on an 18 unit grid
with the hotbar at y=142 and the storage rows at y=84, and GLFW wants window
pixels while the overlay works in framebuffer pixels, so the two differ by the
framebuffer-to-window ratio on a scaled display.

Related: [[fabric-jni-bridge]].
