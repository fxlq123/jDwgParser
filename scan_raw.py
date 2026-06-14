data = open('210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg', 'rb').read()

# Scan with raw 2-byte LE (no bit15 continuation)
print("=== Raw 2-byte LE MS (MS = lo + hi*256, no continuation) ===")
pos = 0x52b9
obj_count = 0
type_counts = {}
while pos < len(data) - 4 and obj_count < 100:
    lo = data[pos] & 0xFF
    hi = data[pos+1] & 0xFF
    ms = lo + (hi << 8)
    if ms <= 2 or ms > 16384:
        pos += 1
        continue
    data_start = pos + 2
    next_pos = data_start + ms
    if next_pos > len(data):
        pos += 1
        continue
    # Read BS type
    b0 = data[data_start] & 0xFF
    opcode = (b0 >> 6) & 3
    if opcode == 1 and next_pos > data_start + 1:
        bits = format(b0, '08b') + format(data[data_start+1], '08b')
        tc = int(bits[2:10], 2)
    elif opcode == 0 and next_pos > data_start + 2:
        bits = format(b0, '08b') + format(data[data_start+1], '08b') + format(data[data_start+2], '08b')
        sub = bits[2:18]
        tc = int(sub[:8], 2) | (int(sub[8:16], 2) << 8)
    elif opcode == 2: tc = 0
    elif opcode == 3: tc = 256
    else: tc = -1
    
    if tc >= 0 and tc < 256:  # reasonable type code
        obj_count += 1
        type_counts[tc] = type_counts.get(tc, 0) + 1
        label = ''
        if tc == 0x30: label = ' **BLOCK_HEADER**'
        elif tc == 0x07: label = ' **INSERT**'
        elif tc == 0x05: label = ' LINE'
        elif tc == 0x01: label = ' TEXT'
        elif tc == 0x04: label = ' ARC'
        elif tc == 0x03: label = ' CIRCLE'
        if 0 < tc < 10 or tc == 0x30 or tc == 0x20:
            print(f'[{obj_count:3d}] pos=0x{pos:04x} MS={ms:5d} type=0x{tc:02x}({tc:2d}){label}')
        pos = next_pos
    else:
        pos += 1

print(f"\nTotal objects: {obj_count}")
print(f"Type distribution: {sorted(type_counts.items())[:20]}")
