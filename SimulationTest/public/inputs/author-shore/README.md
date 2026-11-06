# Owned author shore construction example

Choose one of `ini`, `json` or `hjson` as the normal content root, enable its `package`, and start as `spbuilder`. This standalone package combines the existing author constructor, a new `spshipyard`, and the already supported `spcruiser` from the author-water example. It adds no engine or menu ID registration. The three formats use the same readers, inherited definitions and runtime rules; Hjson includes a real comment.

The owned 800×600 map uses 20-unit tiles. Its vertical water channel spans x=180 through x=340; the other columns are land. The existing normal menu's starting constructors remain on land. The map uses the existing `maps/p001-controlled.tmx` entry, so it needs no new map or spawn-file interpreter.

Select the own constructor at (110,210), move toward (170,210), choose `spshipyard`, and place it at (250,210). The whole five-row placement footprint must be water. Normal construction charges 700 once and uses the configured .004 rate, including the existing turn/preparation rules. With the initial 3000 budget, completion leaves 2300 and one 1200-HP factory. An unfinished factory offers no production action. Dry ground, a footprint crossing the shore, an overlapping placement footprint and an out-of-map footprint are rejected without a second site or payment.

Select the completed factory and buy the existing `spcruiser` for 350 at its unchanged .002 production rate. The same sea exit creates the owned ship; the budget becomes 1950. After it has left the factory, select it near (249,345) and move to (250,430), still inside the channel. Ordinary navigation uses the existing 4-unit arrival radius. This example requires neither an upgrade nor sandbox placement commands.

The fixed-nonce Core fixtures separately declare construction at step180 after an eastward approach, a completed factory by499, the production command at500, birth at1000 and a legal-water move at1200. Normal Session fixtures use actual selections and runtime-generated object IDs with complete construction/production/payment history. These are distinct from native Windows wall-clock operation.

All public images are unchanged repository-owned fixture pixels: constructor graphics from author-construction, building graphics from author-upgrade, and ship graphics plus ground tiles from author-water. The ship's definition and inherited weapon/movement fields are unchanged. Each format is a standalone example; do not enable it alongside another example declaring the same constructor or ship IDs.

Private Windows validation borrows qualified original sea-factory body/dead graphics and heavy-battleship body/dead/turret graphics. There is no separate qualified sea-factory back image, so that private presentation omits the optional back; the public owned back remains. Borrowed artwork does not imply unique artwork for the new factory or original-game runtime compatibility. Original and extracted pixels are not distributed here.
