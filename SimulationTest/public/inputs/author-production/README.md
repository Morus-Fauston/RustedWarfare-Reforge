# Owned author factory example

The INI, JSON and Hjson packages describe the same preplaced `spfactory`: an ordinary 1200 HP, radius 30 LAND building with the existing collision footprint `-1,-1,1,1`, placement footprint `-1,-1,1,3`, construction price 700 and rate `.001`. Each format inherits `factory-base.ini` through the shared content reader. The factory adds `productionUnits=builder,c_tank` and `exitProfile=NATIVE_LAND`.

Product prices and rates come from each referenced unit's existing `price` and `buildSpeed`: builder costs 500, tank costs 350, both use binary32 `.002`. Starting with 3000, paying for both leaves 2150. The existing FIFO produces the first at step 500 and the second at step 1001 when both are queued before step zero. Cancellation refunds the requested pending item and retires its nonce. A factory must finish construction before accepting production.

`productionUnits` and `exitProfile` are optional together. Omitting both keeps the plain construction body introduced by ENG-P010-011. Product IDs must be unique and resolve in the final enabled content set. Unknown exits, dangling targets, missing build metadata, nonfinite values and incompatible capabilities are rejected. Existing exit profiles are selected by their enum names:

| Exit | Foundation | Products |
| --- | --- | --- |
| `NATIVE_LAND` | LAND | Existing LAND units with valid build metadata |
| `CUSTOM_MECH` | LAND | Existing LAND units using the implemented alternating finite exit |
| `NATIVE_AIR` | LAND | Armed AIR units with the supported rotor capability |
| `NATIVE_SEA` | WATER | Armed WATER units |

These selections reuse the current birth/protection/terrain rules. They do not add exit offsets, collision search, teleportation or another queue. An external hard wall can leave a completed unit blocked; an explicit recovery move is separate. Native format identities and finite qualification remain strict. Upgrades belong to ENG-P010-013 and the normal shoreline factory chain to ENG-P010-014.

The normal content menu can select `spfactory` directly. The existing controlled map supplies preplaced factories; this input is not a normal builder acquisition example. Select the owned factory, left-click a product to queue it and right-click that card to cancel one. Current authorized actions determine the cards, including long lists. Longer lists scroll; clipped cards are not advertised as usable. Names fall back to content IDs. The full tooltip includes price, duration and failures, while a separate count line remains visible when a long title is shortened.

`extended/` changes only content: it inherits five more tank IDs, including `sp_tank_7_with_long_name`, and appends them to the same product list. No product or menu name is registered in engine code. All public files use repository-owned images. Private Windows evidence uses qualified original resources and stays outside this public input.

The formal Core adapter uses `author-factory` for the plain body and `author-producer` for a body with production. Both call the same author content reader and verify the declared capability; a wrong label cannot hide or invent a queue. The formal Core fixtures use the author factory source plus the existing strict native builder and tank inputs. The tank's native global replacement declaration remains in that separate explicit Core source. The normal package removes that declaration, with identical gameplay fields, because a standalone enabled content package must not request a global replacement. Whole-package loading and ordinary acquisition are exercised separately by the Session plans.

Image and map provenance: factory/body/back/dead come unchanged from the public `author-construction` example; builder metadata/images come from `three-format-economy/start`; tank metadata/images, the controlled map and terrain come from `three-format-tanks`. Those are existing owned test assets. The five additional tank files only inherit the same owned tank declaration. No original-game files, extracted pixels or third-party source code are included here.
