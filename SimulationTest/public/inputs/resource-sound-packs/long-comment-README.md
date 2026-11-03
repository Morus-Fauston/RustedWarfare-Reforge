# Continued Vorbis header fixture

long-comment.ogg is independently encoded from the existing owned880Hz user-tone.wav using FFmpeg/libvorbis quality4. A generated ffmetadata comment contains the ASCII string owned repeated20000 times, forcing a comment packet across Ogg pages. No sampled or original audio. Decoding requires no encoder. The source WAV remains unchanged. Nominal sample count3528 at22050Hz mono. The encoder and exact private provenance are recorded in the008 evidence, separately from playback evidence.
