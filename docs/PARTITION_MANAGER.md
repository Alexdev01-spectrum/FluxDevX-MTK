# FluxDevX-MTK Partition Manager

## Scatter-driven flashing

Partition selection is driven by a MediaTek scatter file supplied by the user. The parser accepts the common TXT scatter form and XML-style scatter representation.

The UI/backend should present the scatter's partition name, filename, region, start address and partition size. A selected image must not exceed the scatter-described partition capacity.

## Readback

Readback writes device data to a user-selected document URI in chunks. The operation reports progress and never overwrites a source image.

## Flash

Flash reads the selected image in bounded chunks and sends each chunk to the device backend at the scatter-defined partition offset. The backend is responsible for the actual DA/V5/V6 device protocol and must reject writes when authentication or DA compatibility requirements are not met.

## Erase

Erase is modeled as a separate explicit operation. Protected metadata/boot partitions are rejected by the generic safety layer.

## Safety

- Never infer a partition offset from a filename.
- Require an exact scatter partition selection.
- Check image size against `partition_size` before transmission.
- Honor `is_download`.
- Keep preloader/GPT metadata protected by default.
- Require an explicit confirmation in the UI before flash or erase.
- Authentication files are supplied by the user and are not generated or bypassed by FluxDevX-MTK.
