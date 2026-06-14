data = open('210-83-C30302 拨杆座（改2024.06.06）--30件.DWG.dwg', 'rb').read()

# Let me scan from 0x4fa0 to 0x5100 to find handles section
# Try: pairs of (short_le, short_le) entries

# Print bytes
for row_start in range(0x4fa0, 0x50c0, 16):
    hex_part = ' '.join(f'{b:02x}' for b in data[row_start:row_start+16])
    ascii_part = ''.join(chr(b) if 32 <= b < 127 else '.' for b in data[row_start:row_start+16])
    print(f'{row_start:04x}: {hex_part}  {ascii_part}')

print()

# Try: 4-byte entries (2-byte handle, 2-byte offset)
print("=== Try: 2-byte LE handle + 2-byte LE offset ===")
for start in range(0x4fa0, 0x4fc0):
    pos = start
    count = 0
    ok = True
    first_h = 0
    first_o = 0
    while pos < 0x52b8 and count < 20:
        h = data[pos] | (data[pos+1] << 8)
        o = data[pos+2] | (data[pos+3] << 8)
        pos += 4
        if h == 0 and o == 0: continue
        if count == 0:
            first_h = h
            first_o = o
        count += 1
        if o > 0x5400 and o < 0x5000:
            ok = False
            break
    if ok and count > 5 and first_h == 1 and first_o > 0x5000:
        print(f"  start=0x{start:04x}: {count} entries, first h=0x{first_h:x} o=0x{first_o:x}")
