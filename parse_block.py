data = open('210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg', 'rb').read()

def read_mc(buf, idx):
    result = 0
    count = 0
    while True:
        b = buf[idx] & 0xFF
        idx += 1
        count += 1
        result = (result << 7) | (b & 0x7F)
        if (b & 0x80) == 0: break
        if count > 5: break
    return result, idx

def read_ms16(buf, idx):
    result = 0
    shift = 0
    while True:
        lo = buf[idx] & 0xFF
        hi = buf[idx+1] & 0xFF
        w = lo | (hi << 8)
        result |= (w & 0x7FFF) << shift
        idx += 2
        if (w & 0x8000) == 0: break
        shift += 15
    return result, idx

def read_bit_short(obj, bit_pos):
    byte_idx = bit_pos >> 3
    bit_in_byte = bit_pos & 7
    b0 = obj[byte_idx] & 0xFF
    opcode = (b0 >> (6 - bit_in_byte)) & 3  # first 2 bits
    bit_pos += 2
    
    if opcode == 0:  # 16-bit
        val = 0
        for i in range(16):
            byte_idx = bit_pos >> 3
            bit_off = 7 - (bit_pos & 7)
            val = (val << 1) | ((obj[byte_idx] >> bit_off) & 1)
            bit_pos += 1
        return val, bit_pos
    elif opcode == 1:  # 8-bit
        val = 0
        for i in range(8):
            byte_idx = bit_pos >> 3
            bit_off = 7 - (bit_pos & 7)
            val = (val << 1) | ((obj[byte_idx] >> bit_off) & 1)
            bit_pos += 1
        return val, bit_pos
    elif opcode == 2:
        return 0, bit_pos
    else:
        return 256, bit_pos

def read_handle_bits(obj, bit_pos):
    # Handle: 4 bits code, 4 bits counter
    # Actually: let me read 4+4+... based on encoding
    # Simple: 4+4 = 8 bits
    byte_idx = bit_pos >> 3
    bit_in_byte = bit_pos & 7
    code = 0
    for i in range(4):
        byte_idx2 = (bit_pos + i) >> 3
        bit_off = 7 - ((bit_pos + i) & 7)
        code = (code << 1) | ((obj[byte_idx2] >> bit_off) & 1)
    counter = 0
    for i in range(4):
        byte_idx2 = (bit_pos + 4 + i) >> 3
        bit_off = 7 - ((bit_pos + 4 + i) & 7)
        counter = (counter << 1) | ((obj[byte_idx2] >> bit_off) & 1)
    bit_pos += 8
    
    # Handle value: if counter != 0, handle = counter << 16 | (code+1)?
    # Actually handle encoding: code = handle group, counter = offset in group
    # handle = ((code + 1) << 16) | counter
    if counter == 0 and code == 0:
        return 0, bit_pos
    return (code << 16) | counter, bit_pos

def read_text_bits(obj, bit_pos, length):
    # Read length bytes
    result = bytearray()
    for i in range(length):
        byte = 0
        for j in range(8):
            bp = bit_pos + i*8 + j
            byte_idx = bp >> 3
            bit_off = 7 - (bp & 7)
            byte = (byte << 1) | ((obj[byte_idx] >> bit_off) & 1)
        result.append(byte)
    return result.decode('ascii', errors='replace'), bit_pos + length*8

# Now let's try to parse object at 0x52b9
pos = 0x52b9
ms, data_start = read_ms16(data, pos)
print(f"Object at 0x{pos:04x}: MS={ms}, data starts at 0x{data_start:04x}")

obj = data[data_start:data_start+ms]
print(f"Object data (first 32 bytes): {' '.join(f'{b:02x}' for b in obj[:32])}")
print(f"Object as text: {''.join(chr(b) if 32 <= b < 127 else '.' for b in obj[:64])}")
print()

# Parse: BS (type code) from bit 0
tc, bp = read_bit_short(obj, 0)
print(f"Type code: 0x{tc:02x} ({tc})")

# Entity header: bitsize (RL = 32 bits at current bit position)
# bitsize = 32 bits
# After reading BS, bit position is at bp
bitsize_val = 0
for i in range(32):
    bp_i = bp + i
    byte_idx = bp_i >> 3
    bit_off = 7 - (bp_i & 7)
    bitsize_val = (bitsize_val << 1) | ((obj[byte_idx] >> bit_off) & 1)
bp += 32
print(f"bitsize RL = {bitsize_val}")

# Read object handle
handle, bp = read_handle_bits(obj, bp)
print(f"object handle = 0x{handle:x}")

# Read EED (BS)
eed_size, bp = read_bit_short(obj, bp)
print(f"EED size = {eed_size}")
if eed_size > 0 and eed_size < 200:
    # Skip 1 byte (handle) + eed_size bytes
    bp += 8 + eed_size * 8

# Common entity data for R2000:
# preview_exists (B), then preview data if true
# entmode (BB = 2 bits)
# num_reactors (BL), then reactors
# nolinks?
# color (BS)
# ltype_scale (BD)
# ltype_flags (BB), plotstyle_flags (BB)
# invisible (BS)
# linewt (RC = 8 bits)

# preview_exists
pe_byteidx = bp >> 3
pe_bitoff = 7 - (bp & 7)
pe = (obj[pe_byteidx] >> pe_bitoff) & 1
bp += 1
print(f"preview_exists = {pe}")

if pe:
    # preview size (RL = 32 bits)
    preview_size = 0
    for i in range(32):
        bp_i = bp + i
        byte_idx = bp_i >> 3
        bit_off = 7 - (bp_i & 7)
        preview_size = (preview_size << 1) | ((obj[byte_idx] >> bit_off) & 1)
    bp += 32
    print(f"preview_size = {preview_size}")
    if 0 < preview_size < 100000:
        bp += preview_size * 8

# entmode (2 bits)
entmode = 0
for i in range(2):
    bp_i = bp + i
    byte_idx = bp_i >> 3
    bit_off = 7 - (bp_i & 7)
    entmode = (entmode << 1) | ((obj[byte_idx] >> bit_off) & 1)
bp += 2
print(f"entmode = {entmode}")

# num_reactors (BL)
nre, bp = read_bit_short(obj, bp)
print(f"num_reactors = {nre}")

for i in range(nre):
    # handle (H)
    rh, bp = read_handle_bits(obj, bp)
    print(f"  reactor handle {i} = 0x{rh:x}")

# color (BS)
color, bp = read_bit_short(obj, bp)
print(f"color = {color}")

# ltype_scale (BD) - but for BLOCK_HEADER, maybe skipped
# Actually let's skip and try reading text directly

# Let's try to read block_name (text)
# block_name might be at specific position in the object

# Common entity data for non-entity object (BLOCK_HEADER is not an entity)
# Actually BLOCK_HEADER has different common data format

# Let me try: after object handle + EED + common (maybe shorter for non-entity),
# read text as block name

# Try reading text at various bit positions
print(f"\nTrying to find block name text...")
print(f"Current bit position: {bp} (byte {bp >> 3})")

# Try: skip 4 more bytes of common data and read text
for skip_bits in [0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112]:
    test_bp = bp + skip_bits
    try:
        text_len, new_bp = read_bit_short(obj, test_bp)
        if 0 < text_len < 64:
            text_bytes = []
            valid = True
            for i in range(text_len):
                b = 0
                for j in range(8):
                    bpi = test_bp + 2 + i*8 + j  # after BS opcode
                    # Actually simpler: text data after the BS field of length
                    pass
            # Read after BS
            after_bs_bp = new_bp
            text_result = ''
            for i in range(text_len):
                ch = 0
                for j in range(8):
                    bpi = after_bs_bp + i*8 + j
                    byte_idx = bpi >> 3
                    bit_off = 7 - (bpi & 7)
                    if byte_idx >= len(obj):
                        valid = False
                        break
                    ch = (ch << 1) | ((obj[byte_idx] >> bit_off) & 1)
                if not valid: break
                if 32 <= ch < 127 or ch == 95 or ch == 9:
                    text_result += chr(ch)
                else:
                    text_result += '.'
            if valid and len(text_result) > 1 and all(c != '.' for c in text_result[:2]):
                print(f"  skip={skip_bits} bits, len={text_len}: '{text_result[:30]}'")
    except:
        pass
