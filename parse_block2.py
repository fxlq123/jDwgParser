data = open('210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg', 'rb').read()

# Object at 0x52b9, MS=114 (bytes 72 00), object data 114 bytes from 0x52bb
obj = data[0x52bb:0x52bb+114]

print(f"Object data ({len(obj)} bytes):")
for i in range(0, len(obj), 16):
    hex_part = ' '.join(f'{b:02x}' for b in obj[i:i+16])
    ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in obj[i:i+16])
    print(f"  {i:04x}: {hex_part}  {ascii_part}")

# Look for "Model" or other block names
print("\nLooking for text 'Model' in object data:")
for i in range(len(obj) - 5):
    if obj[i:i+5] == b'Model' or obj[i:i+5] == b'*Model':
        print(f"  Found 'Model' at offset {i} (0x{i:x})")
        print(f"  Context: {obj[max(0,i-5):i+20]}")

for i in range(len(obj) - 8):
    text = obj[i:i+8]
    if all(32 <= b < 127 for b in text):
        print(f"  Possible text at {i}: {text.decode('ascii')}")

# Look for small bytes that could be text length
print("\nPossible text fields (byte sequence: small byte + ASCII):")
for i in range(len(obj) - 20):
    length = obj[i]
    if 1 < length < 30:
        test_text = obj[i+1:i+1+length]
        if all(32 <= b < 127 or b in (0, 9, 13, 10) for b in test_text):
            try:
                text = test_text.decode('ascii')
                print(f"  Offset {i}: len={length}, text='{text}'")
            except:
                pass

# Let me also look at the object header
print("\nByte-level analysis of object header:")
print(f"  Byte 0 (0x4c): {obj[0]:02x} = {obj[0]:08b}")
print(f"  Byte 1 (0x12): {obj[1]:02x} = {obj[1]:08b}")
print(f"  Byte 2-5: {' '.join(f'{obj[i]:02x}' for i in range(2,6))}")
print(f"  Byte 6: {obj[6]:02x}")
print(f"  Byte 7: {obj[7]:02x}")
print(f"  Byte 8: {obj[8]:02x}")

# Try: object header format is MS | BS | [data]
# Actually, let me try different starting points
print("\n--- Try: MS byte = 0x72 (just the byte, no continuation word)")
obj2 = data[0x52ba:0x52ba+114]
print(f"Object data ({len(obj2)} bytes):")
for i in range(0, len(obj2), 16):
    hex_part = ' '.join(f'{b:02x}' for b in obj2[i:i+16])
    ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in obj2[i:i+16])
    print(f"  {i:04x}: {hex_part}  {ascii_part}")

print("\n--- Try: MS 2-byte LE 0x0072 = 114, data from 0x52bb for 114 bytes")
# This is what we had before
# Now try reading different fields from this data
# Format:
# [BS (type_code)] -> consumed 10 bits
# [RL (bitsize)] -> 32 bits from bit 10
# [H (handle)] -> 8 bits (4+4)
# [BS (EED_size)]
# ...

# Let me calculate:
print("\nField-by-field decode:")
obj_bytes = data[0x52bb:0x52bb+114]
bit_pos = 0

def read_bits(bytes_arr, bp, n):
    result = 0
    for i in range(n):
        byte_idx = (bp + i) >> 3
        bit_off = 7 - ((bp + i) & 7)
        result = (result << 1) | ((bytes_arr[byte_idx] >> bit_off) & 1)
    return result, bp + n

def read_bs(bytes_arr, bp):
    opcode, bp = read_bits(bytes_arr, bp, 2)
    if opcode == 0:
        val, bp = read_bits(bytes_arr, bp, 16)
    elif opcode == 1:
        val, bp = read_bits(bytes_arr, bp, 8)
    elif opcode == 2:
        val = 0
    else:
        val = 256
    return val, bp

def read_handle(bytes_arr, bp):
    # 4 bits code + 4 bits counter
    code, bp = read_bits(bytes_arr, bp, 4)
    counter, bp = read_bits(bytes_arr, bp, 4)
    return (code << 16) | counter, bp

# Type code
tc, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"[0x{0:04x} bits 0-{bit_pos-1}] Type code = 0x{tc:02x} ({tc})")

# bitsize (RL = 32 bits)
bitsize, bit_pos = read_bits(obj_bytes, bit_pos, 32)
print(f"[bit {bit_pos-32}-{bit_pos-1}] bitsize = {bitsize}")

# Handle
handle, bit_pos = read_handle(obj_bytes, bit_pos)
print(f"[bit {bit_pos-8}-{bit_pos-1}] object handle = 0x{handle:x}")

# EED size
eed, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"EED size = {eed} (at bit {bit_pos})")

if 0 < eed < 200:
    # 1 byte handle + eed bytes
    bit_pos += 8 + eed * 8

# Common entity data
# preview_exists (B)
pe, bit_pos = read_bits(obj_bytes, bit_pos, 1)
print(f"preview_exists = {pe}")

if pe:
    # RL size
    psize, bit_pos = read_bits(obj_bytes, bit_pos, 32)
    print(f"  preview size = {psize}")
    bit_pos += psize * 8

# entmode (2 bits)
entmode, bit_pos = read_bits(obj_bytes, bit_pos, 2)
print(f"entmode = {entmode}")

# num_reactors (BL)
nre, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"num_reactors = {nre}")

for i in range(nre):
    rh, bit_pos = read_handle(obj_bytes, bit_pos)
    print(f"  reactor {i}: 0x{rh:x}")

# color (BS)
color, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"color = {color}")

# ltype_scale (BD) - skip 16+? bits for now
# Actually BD: opcode 2 bits then conditionally data
bd_opcode, bit_pos = read_bits(obj_bytes, bit_pos, 2)
print(f"ltype_scale opcode = {bd_opcode}")
if bd_opcode == 0:
    # 64 bits (raw double)
    bit_pos += 64
    print("  skipped 64 bits (raw double)")
elif bd_opcode == 1:
    # 16 bits? Actually BD opcode 1: 1 sign bit + 56 bits?
    # Let me just skip
    bit_pos += 64
    print("  skipped 64 bits")
elif bd_opcode == 2:
    # default value 1.0
    print("  default 1.0")
else:
    # default value 0.0
    print("  default 0.0")

# ltype_flags (2 bits)
ltflags, bit_pos = read_bits(obj_bytes, bit_pos, 2)
print(f"ltype_flags = {ltflags}")
# plotstyle_flags (2 bits)
psflags, bit_pos = read_bits(obj_bytes, bit_pos, 2)
print(f"plotstyle_flags = {psflags}")

# invisible (BS)
inv, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"invisible = {inv}")

# linewt (RC = 8 bits)
linewt, bit_pos = read_bits(obj_bytes, bit_pos, 8)
print(f"linewt = {linewt}")

print(f"\nAfter common entity data, bit position = {bit_pos} (byte {bit_pos >> 3})")

# BLOCK_HEADER specific:
# block_name (T)
bl_name_len, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"block_name length = {bl_name_len}")

if 0 < bl_name_len < 100 and bit_pos + bl_name_len*8 < len(obj_bytes)*8:
    bl_name = bytearray()
    for i in range(bl_name_len):
        ch, bit_pos = read_bits(obj_bytes, bit_pos, 8)
        bl_name.append(ch)
    try:
        print(f"BLOCK NAME: '{bl_name.decode('ascii', errors='replace')}'")
    except:
        print(f"BLOCK NAME bytes: {list(bl_name)}")

# flags (BS)
flags, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"flags = {flags}")

# base_point (3BD)
for i in range(3):
    bp_opcode, bit_pos = read_bits(obj_bytes, bit_pos, 2)
    if bp_opcode == 0:
        # 64 bits
        bit_pos += 64
    elif bp_opcode == 1:
        bit_pos += 64
    # else: default

# xref_path (T)
xref_len, bit_pos = read_bs(obj_bytes, bit_pos)
print(f"xref_path length = {xref_len}")
if 0 < xref_len < 100 and bit_pos + xref_len*8 < len(obj_bytes)*8:
    xref = bytearray()
    for i in range(xref_len):
        ch, bit_pos = read_bits(obj_bytes, bit_pos, 8)
        xref.append(ch)
    try:
        print(f"xref_path: '{xref.decode('ascii', errors='replace')}'")
    except:
        pass
