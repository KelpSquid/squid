package squid.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An Ogg Vorbis decoder (the format Minecraft's own sounds and music use), written for Squid from the Vorbis I
 * description. An .ogg file is a row of pages that carry packets: three header packets (what the sound is, its tags,
 * and the codebooks and settings), then one packet per block of sound. Each block stores a rough outline of the
 * sound's loudness per frequency (the floor) and the fine detail on top of it (the residue), both squeezed with
 * codebooks (Huffman codes, sometimes pointing at little vectors of numbers). Decoding rebuilds the outline, adds the
 * detail, undoes stereo coupling, turns frequencies back into sound with an inverse MDCT, and blends each block into
 * the next with Vorbis's smooth window. The last page says exactly how long the sound is, so the end is trimmed to it.
 */
public final class Vorbis {
    private Vorbis() {
    }

    public static boolean is(byte[] d) {
        return d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
    }

    // ---- Ogg: pages and packets ----

    /** The packets of the first logical stream in an Ogg file, and the last granule position (the length in samples). */
    private static List<byte[]> packets(byte[] d, long[] lastGranule) {
        List<byte[]> packets = new ArrayList<>();
        java.io.ByteArrayOutputStream current = new java.io.ByteArrayOutputStream();
        int at = 0;
        int serial = 0;
        boolean first = true;
        while (at + 27 <= d.length) {
            if (d[at] != 'O' || d[at + 1] != 'g' || d[at + 2] != 'g' || d[at + 3] != 'S') {
                at++;
                continue;
            }
            long granule = 0;
            for (int i = 7; i >= 0; i--) granule = (granule << 8) | (d[at + 6 + i] & 0xFF);
            int pageSerial = (d[at + 14] & 0xFF) | ((d[at + 15] & 0xFF) << 8) | ((d[at + 16] & 0xFF) << 16) | ((d[at + 17] & 0xFF) << 24);
            int segments = d[at + 26] & 0xFF;
            if (at + 27 + segments > d.length) break;
            int body = at + 27 + segments;
            if (first) {
                serial = pageSerial;
                first = false;
            }
            if (pageSerial != serial) { // another stream in the same file: not ours
                int size = 0;
                for (int i = 0; i < segments; i++) size += d[at + 27 + i] & 0xFF;
                at = body + size;
                continue;
            }
            for (int i = 0; i < segments; i++) {
                int lace = d[at + 27 + i] & 0xFF;
                if (body + lace > d.length) break;
                current.write(d, body, lace);
                body += lace;
                if (lace < 255) { // a packet ends here
                    packets.add(current.toByteArray());
                    current.reset();
                }
            }
            if (granule != -1) lastGranule[0] = granule;
            at = body;
        }
        return packets;
    }

    // ---- Reading bits, least significant first (Vorbis's way). Past the end it reads zeros and says so. ----

    private static final class Reader {
        private final byte[] data;
        private long position;
        boolean ended;

        Reader(byte[] data) {
            this.data = data;
        }

        int bit() {
            int byteIndex = (int) (position >>> 3);
            if (byteIndex >= data.length) {
                ended = true;
                return 0;
            }
            int bit = (data[byteIndex] >>> (position & 7)) & 1;
            position++;
            return bit;
        }

        int read(int n) {
            int value = 0;
            for (int i = 0; i < n; i++) value |= bit() << i;
            return value;
        }

        long readLong(int n) {
            long value = 0;
            for (int i = 0; i < n; i++) value |= (long) bit() << i;
            return value;
        }
    }

    private static int ilog(long x) {
        int n = 0;
        while (x > 0) {
            n++;
            x >>>= 1;
        }
        return n;
    }

    private static float float32(long x) {
        long mantissa = x & 0x1FFFFF;
        long exponent = (x & 0x7FE00000L) >> 21;
        if ((x & 0x80000000L) != 0) mantissa = -mantissa;
        return (float) (mantissa * Math.pow(2, exponent - 788));
    }

    // ---- Codebooks ----

    private static final class Codebook {
        int dimensions;
        int entries;
        int[] lengths;
        // A binary tree of the Huffman codes: children[node*2 + bit], negative values are leaves (-1 - entry)
        int[] tree;
        float[][] vectors; // the numbers each entry stands for, or null for scalar-only books

        static Codebook read(Reader r) {
            if (r.read(24) != 0x564342) throw new IllegalArgumentException("bad codebook");
            Codebook b = new Codebook();
            b.dimensions = r.read(16);
            b.entries = r.read(24);
            b.lengths = new int[b.entries];
            if (r.read(1) == 0) {
                boolean sparse = r.read(1) == 1;
                for (int i = 0; i < b.entries; i++) {
                    if (!sparse || r.read(1) == 1) b.lengths[i] = r.read(5) + 1;
                }
            } else {
                int entry = 0;
                int length = r.read(5) + 1;
                while (entry < b.entries) {
                    int count = r.read(ilog(b.entries - entry));
                    for (int i = 0; i < count && entry < b.entries; i++) b.lengths[entry++] = length;
                    length++;
                }
            }
            int lookup = r.read(4);
            if (lookup == 1 || lookup == 2) {
                float minimum = float32(r.readLong(32));
                float delta = float32(r.readLong(32));
                int valueBits = r.read(4) + 1;
                boolean sequence = r.read(1) == 1;
                int lookupValues = lookup == 1 ? lookup1Values(b.entries, b.dimensions) : b.entries * b.dimensions;
                int[] multiplicands = new int[lookupValues];
                for (int i = 0; i < lookupValues; i++) multiplicands[i] = r.read(valueBits);
                b.vectors = new float[b.entries][b.dimensions];
                for (int e = 0; e < b.entries; e++) {
                    float last = 0;
                    int divisor = 1;
                    for (int i = 0; i < b.dimensions; i++) {
                        int offset = lookup == 1 ? (e / divisor) % lookupValues : e * b.dimensions + i;
                        float value = multiplicands[offset] * delta + minimum + last;
                        b.vectors[e][i] = value;
                        if (sequence) last = value;
                        if (lookup == 1) divisor *= lookupValues;
                    }
                }
            } else if (lookup != 0) {
                throw new IllegalArgumentException("bad codebook lookup type");
            }
            b.buildTree();
            return b;
        }

        /** The biggest r where r to the power of dimensions is at most entries. */
        static int lookup1Values(int entries, int dimensions) {
            int r = (int) Math.floor(Math.pow(entries, 1.0 / dimensions));
            while (Math.pow(r + 1, dimensions) <= entries) r++;
            while (r > 0 && Math.pow(r, dimensions) > entries) r--;
            return r;
        }

        /** Gives each used entry the next free code of its length, in order (Vorbis's way), and builds a tree to read them. */
        void buildTree() {
            long[] marker = new long[33];
            long[] codes = new long[entries];
            int used = 0;
            for (int i = 0; i < entries; i++) if (lengths[i] > 0) used++;
            for (int i = 0; i < entries; i++) {
                int len = lengths[i];
                if (len == 0) continue;
                long entry = marker[len];
                if (len < 32 && (entry >>> len) != 0 && used > 1) throw new IllegalArgumentException("codebook has too many codes");
                codes[i] = entry;
                for (int j = len; j > 0; j--) {
                    if ((marker[j] & 1) != 0) {
                        if (j == 1) marker[1]++;
                        else marker[j] = marker[j - 1] << 1;
                        break;
                    }
                    marker[j]++;
                }
                for (int j = len + 1; j < 33; j++) {
                    if ((marker[j] >>> 1) == entry) {
                        entry = marker[j];
                        marker[j] = marker[j - 1] << 1;
                    } else {
                        break;
                    }
                }
            }
            List<int[]> nodes = new ArrayList<>();
            nodes.add(new int[] {0, 0}); // 0 means "no child yet"
            for (int i = 0; i < entries; i++) {
                int len = lengths[i];
                if (len == 0) continue;
                int node = 0;
                for (int k = len - 1; k >= 0; k--) {
                    int bit = (int) ((codes[i] >>> k) & 1);
                    if (k == 0) {
                        nodes.get(node)[bit] = -1 - i;
                    } else {
                        int next = nodes.get(node)[bit];
                        if (next <= 0) {
                            nodes.add(new int[] {0, 0});
                            next = nodes.size() - 1;
                            nodes.get(node)[bit] = next;
                        }
                        node = next;
                    }
                }
            }
            tree = new int[nodes.size() * 2];
            for (int i = 0; i < nodes.size(); i++) {
                tree[i * 2] = nodes.get(i)[0];
                tree[i * 2 + 1] = nodes.get(i)[1];
            }
        }

        /** Reads one entry number, or -1 at the end of the packet (or on a code that doesn't exist). */
        int decode(Reader r) {
            int node = 0;
            for (int guard = 0; guard < 33; guard++) {
                int bit = r.bit();
                if (r.ended) return -1;
                int next = tree[node * 2 + bit];
                if (next < 0) return -1 - next;
                if (next == 0) return -1;
                node = next;
            }
            return -1;
        }
    }

    // ---- The setup: floors, residues, mappings and modes ----

    private static final class Floor {
        int[] partitionClass;
        int[] classDimensions;
        int[] classSubclasses;
        int[] classMasterbook;
        int[][] subclassBooks;
        int multiplier;
        int[] x;
        // The order of x from small to big, and each point's nearest neighbours below and above (worked out once)
        int[] sorted;
        int[] low;
        int[] high;
    }

    private static final class Residue {
        int type;
        int begin;
        int end;
        int partitionSize;
        int classifications;
        int classbook;
        int[][] books;
    }

    private static final class Mapping {
        int[] mux;
        int[] submapFloor;
        int[] submapResidue;
        int[] magnitude;
        int[] angle;
    }

    private record Mode(boolean longBlock, int mapping) {
    }

    /** Decodes a whole .ogg Vorbis file. */
    public static Pcm decode(byte[] d) {
        long[] lastGranule = {-1};
        List<byte[]> packets = packets(d, lastGranule);
        if (packets.size() < 3) throw new IllegalArgumentException("the Vorbis headers are missing");
        // Identification header
        Reader id = new Reader(packets.get(0));
        if (id.read(8) != 1 || !vorbisWord(id)) throw new IllegalArgumentException("not a Vorbis stream");
        id.readLong(32); // version
        int channels = id.read(8);
        int rate = (int) id.readLong(32);
        id.readLong(32);
        id.readLong(32);
        id.readLong(32);
        int[] blocksize = {1 << id.read(4), 1 << id.read(4)};
        // Setup header
        Reader s = new Reader(packets.get(2));
        if (s.read(8) != 5 || !vorbisWord(s)) throw new IllegalArgumentException("the Vorbis setup header is missing");
        Codebook[] books = new Codebook[s.read(8) + 1];
        for (int i = 0; i < books.length; i++) books[i] = Codebook.read(s);
        int times = s.read(6) + 1;
        for (int i = 0; i < times; i++) s.read(16);
        Floor[] floors = new Floor[s.read(6) + 1];
        for (int i = 0; i < floors.length; i++) {
            if (s.read(16) != 1) throw new IllegalArgumentException("floor type 0 isn't supported (very old Vorbis files)");
            floors[i] = readFloor(s);
        }
        Residue[] residues = new Residue[s.read(6) + 1];
        for (int i = 0; i < residues.length; i++) residues[i] = readResidue(s);
        Mapping[] mappings = new Mapping[s.read(6) + 1];
        for (int i = 0; i < mappings.length; i++) mappings[i] = readMapping(s, channels);
        Mode[] modes = new Mode[s.read(6) + 1];
        for (int i = 0; i < modes.length; i++) {
            boolean longBlock = s.read(1) == 1;
            s.read(16);
            s.read(16);
            modes[i] = new Mode(longBlock, s.read(8));
        }

        // The sound: every block's samples are added into these, at their place
        float[][] out = new float[channels][1 << 16];
        long center = -1;  // the previous block's center (in samples), and where output starts
        long start = 0;    // where output starts
        int previousSize = 0;
        long finished = 0; // how many samples are complete
        boolean firstBlock = true;
        for (int p = 3; p < packets.size(); p++) {
            Reader r = new Reader(packets.get(p));
            if (r.read(1) != 0) continue; // not an audio packet
            Mode mode = modes[r.read(ilog(modes.length - 1))];
            int n = blocksize[mode.longBlock() ? 1 : 0];
            boolean previousLong = true;
            boolean nextLong = true;
            if (mode.longBlock()) {
                previousLong = r.read(1) == 1;
                nextLong = r.read(1) == 1;
            }
            float[][] block = decodeBlock(r, n, channels, books, floors, residues, mappings[mode.mapping()]);
            float[] window = window(n, mode.longBlock(), previousLong, nextLong, blocksize[0]);
            // Where this block goes: its center is a quarter of each block's size after the last center
            long blockCenter = firstBlock ? n / 2 : center + previousSize / 4 + n / 4;
            long blockStart = blockCenter - n / 2;
            if (firstBlock) start = blockCenter;
            int needed = (int) (blockStart + n - start);
            if (needed > out[0].length) {
                for (int c = 0; c < channels; c++) out[c] = Arrays.copyOf(out[c], Math.max(out[c].length * 2, needed));
            }
            for (int c = 0; c < channels; c++) {
                float[] o = out[c];
                float[] b = block[c];
                for (int i = 0; i < n; i++) {
                    long at = blockStart + i - start;
                    if (at >= 0) o[(int) at] += b[i] * window[i];
                }
            }
            finished = blockCenter - start;
            center = blockCenter;
            previousSize = n;
            firstBlock = false;
        }
        long total = finished;
        if (lastGranule[0] >= 0 && lastGranule[0] < total) total = lastGranule[0]; // the last page says the real length
        short[] samples = new short[(int) total * channels];
        for (int i = 0; i < total; i++) {
            for (int c = 0; c < channels; c++) {
                int v = Math.round(out[c][i] * 32768);
                samples[i * channels + c] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
        }
        return new Pcm(samples, channels, rate);
    }

    private static boolean vorbisWord(Reader r) {
        for (char c : "vorbis".toCharArray()) if (r.read(8) != c) return false;
        return true;
    }

    private static Floor readFloor(Reader r) {
        Floor f = new Floor();
        int partitions = r.read(5);
        f.partitionClass = new int[partitions];
        int maxClass = -1;
        for (int i = 0; i < partitions; i++) {
            f.partitionClass[i] = r.read(4);
            maxClass = Math.max(maxClass, f.partitionClass[i]);
        }
        f.classDimensions = new int[maxClass + 1];
        f.classSubclasses = new int[maxClass + 1];
        f.classMasterbook = new int[maxClass + 1];
        f.subclassBooks = new int[maxClass + 1][];
        for (int c = 0; c <= maxClass; c++) {
            f.classDimensions[c] = r.read(3) + 1;
            f.classSubclasses[c] = r.read(2);
            if (f.classSubclasses[c] > 0) f.classMasterbook[c] = r.read(8);
            f.subclassBooks[c] = new int[1 << f.classSubclasses[c]];
            for (int j = 0; j < f.subclassBooks[c].length; j++) f.subclassBooks[c][j] = r.read(8) - 1;
        }
        f.multiplier = r.read(2) + 1;
        int rangeBits = r.read(4);
        List<Integer> xs = new ArrayList<>(List.of(0, 1 << rangeBits));
        for (int i = 0; i < partitions; i++) {
            int c = f.partitionClass[i];
            for (int j = 0; j < f.classDimensions[c]; j++) xs.add(r.read(rangeBits));
        }
        f.x = xs.stream().mapToInt(Integer::intValue).toArray();
        int values = f.x.length;
        Integer[] order = new Integer[values];
        for (int i = 0; i < values; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(f.x[a], f.x[b]));
        f.sorted = new int[values];
        for (int i = 0; i < values; i++) f.sorted[i] = order[i];
        f.low = new int[values];
        f.high = new int[values];
        for (int i = 2; i < values; i++) {
            int low = -1;
            int high = -1;
            for (int j = 0; j < i; j++) {
                if (f.x[j] < f.x[i] && (low < 0 || f.x[j] > f.x[low])) low = j;
                if (f.x[j] > f.x[i] && (high < 0 || f.x[j] < f.x[high])) high = j;
            }
            f.low[i] = low;
            f.high[i] = high;
        }
        return f;
    }

    private static Residue readResidue(Reader r) {
        Residue res = new Residue();
        res.type = r.read(16);
        if (res.type > 2) throw new IllegalArgumentException("bad residue type");
        res.begin = r.read(24);
        res.end = r.read(24);
        res.partitionSize = r.read(24) + 1;
        res.classifications = r.read(6) + 1;
        res.classbook = r.read(8);
        int[] cascade = new int[res.classifications];
        for (int i = 0; i < res.classifications; i++) {
            int low = r.read(3);
            int high = r.read(1) == 1 ? r.read(5) : 0;
            cascade[i] = high * 8 + low;
        }
        res.books = new int[res.classifications][8];
        for (int i = 0; i < res.classifications; i++) {
            for (int j = 0; j < 8; j++) res.books[i][j] = (cascade[i] & (1 << j)) != 0 ? r.read(8) : -1;
        }
        return res;
    }

    private static Mapping readMapping(Reader r, int channels) {
        if (r.read(16) != 0) throw new IllegalArgumentException("bad mapping type");
        Mapping m = new Mapping();
        int submaps = r.read(1) == 1 ? r.read(4) + 1 : 1;
        int steps = r.read(1) == 1 ? r.read(8) + 1 : 0;
        m.magnitude = new int[steps];
        m.angle = new int[steps];
        for (int i = 0; i < steps; i++) {
            m.magnitude[i] = r.read(ilog(channels - 1));
            m.angle[i] = r.read(ilog(channels - 1));
        }
        if (r.read(2) != 0) throw new IllegalArgumentException("bad mapping");
        m.mux = new int[channels];
        if (submaps > 1) for (int c = 0; c < channels; c++) m.mux[c] = r.read(4);
        m.submapFloor = new int[submaps];
        m.submapResidue = new int[submaps];
        for (int i = 0; i < submaps; i++) {
            r.read(8);
            m.submapFloor[i] = r.read(8);
            m.submapResidue[i] = r.read(8);
        }
        return m;
    }

    // ---- One block of sound ----

    private static float[][] decodeBlock(Reader r, int n, int channels, Codebook[] books, Floor[] floors, Residue[] residues, Mapping m) {
        int half = n / 2;
        float[][] curve = new float[channels][];
        boolean[] unused = new boolean[channels];
        for (int c = 0; c < channels; c++) {
            curve[c] = floorCurve(r, floors[m.submapFloor[m.mux[c]]], books, half);
            unused[c] = curve[c] == null;
        }
        // A coupled pair is decoded if either of its channels has sound
        boolean[] noResidue = unused.clone();
        for (int i = 0; i < m.magnitude.length; i++) {
            if (!noResidue[m.magnitude[i]] || !noResidue[m.angle[i]]) {
                noResidue[m.magnitude[i]] = false;
                noResidue[m.angle[i]] = false;
            }
        }
        float[][] residue = new float[channels][half];
        for (int sub = 0; sub < m.submapFloor.length; sub++) {
            List<Integer> inMap = new ArrayList<>();
            for (int c = 0; c < channels; c++) if (m.mux[c] == sub) inMap.add(c);
            float[][] vectors = new float[inMap.size()][];
            boolean[] skip = new boolean[inMap.size()];
            for (int k = 0; k < inMap.size(); k++) {
                vectors[k] = residue[inMap.get(k)];
                skip[k] = noResidue[inMap.get(k)];
            }
            decodeResidue(r, residues[m.submapResidue[sub]], books, vectors, skip, half);
        }
        // Undo the coupling, last step first
        for (int i = m.magnitude.length - 1; i >= 0; i--) {
            float[] mag = residue[m.magnitude[i]];
            float[] ang = residue[m.angle[i]];
            for (int k = 0; k < half; k++) {
                float a = mag[k];
                float b = ang[k];
                if (a > 0) {
                    if (b > 0) {
                        ang[k] = a - b;
                    } else {
                        ang[k] = a;
                        mag[k] = a + b;
                    }
                } else {
                    if (b > 0) {
                        ang[k] = a + b;
                    } else {
                        ang[k] = a;
                        mag[k] = a - b;
                    }
                }
            }
        }
        float[][] block = new float[channels][];
        for (int c = 0; c < channels; c++) {
            float[] spectrum = new float[half];
            if (curve[c] != null) for (int k = 0; k < half; k++) spectrum[k] = curve[c][k] * residue[c][k];
            block[c] = imdct(spectrum, n);
        }
        return block;
    }

    private static final int[] FLOOR1_RANGE = {256, 128, 86, 64};
    private static final float[] INVERSE_DB = new float[256];

    static {
        // Floor 1's loudness steps: 256 values from about 1e-7 up to 1, each about 0.55 dB louder than the last
        for (int i = 0; i < 256; i++) INVERSE_DB[i] = (float) (1.0649863e-07 * Math.pow(1.0649863, i));
    }

    /** The floor: a line through a few points, giving each frequency's loudness. Null if this channel is silent. */
    private static float[] floorCurve(Reader r, Floor f, Codebook[] books, int half) {
        if (r.read(1) == 0) return null;
        int range = FLOOR1_RANGE[f.multiplier - 1];
        int values = f.x.length;
        int[] y = new int[values];
        int bits = ilog(range - 1);
        y[0] = r.read(bits);
        y[1] = r.read(bits);
        int offset = 2;
        for (int partition : f.partitionClass) {
            int dims = f.classDimensions[partition];
            int subBits = f.classSubclasses[partition];
            int mask = (1 << subBits) - 1;
            int value = 0;
            if (subBits > 0) {
                value = books[f.classMasterbook[partition]].decode(r);
                if (value < 0) return null;
            }
            for (int j = 0; j < dims; j++) {
                int book = f.subclassBooks[partition][value & mask];
                value >>>= subBits;
                if (book >= 0) {
                    int v = books[book].decode(r);
                    if (v < 0) return null;
                    y[offset + j] = v;
                } else {
                    y[offset + j] = 0;
                }
            }
            offset += dims;
        }
        if (r.ended) return null;
        // Step 1: each point is stored as a difference from the line between its neighbours
        int[] finalY = new int[values];
        boolean[] used = new boolean[values];
        finalY[0] = y[0];
        finalY[1] = y[1];
        used[0] = used[1] = true;
        for (int i = 2; i < values; i++) {
            int lo = f.low[i];
            int hi = f.high[i];
            int predicted = renderPoint(f.x[lo], finalY[lo], f.x[hi], finalY[hi], f.x[i]);
            int val = y[i];
            int highRoom = range - predicted;
            int lowRoom = predicted;
            int room = Math.min(highRoom, lowRoom) * 2;
            if (val != 0) {
                used[lo] = used[hi] = used[i] = true;
                if (val >= room) {
                    finalY[i] = highRoom > lowRoom ? val - lowRoom + predicted : predicted - val + highRoom - 1;
                } else {
                    finalY[i] = (val & 1) == 1 ? predicted - (val + 1) / 2 : predicted + val / 2;
                }
            } else {
                finalY[i] = predicted;
            }
        }
        // Step 2: draw lines between the used points, from left to right, then turn steps into loudness
        int[] line = new int[half];
        int lx = 0;
        int ly = finalY[f.sorted[0]] * f.multiplier;
        int hx = 0;
        int hy = ly;
        for (int k = 1; k < values; k++) {
            int i = f.sorted[k];
            if (!used[i]) continue;
            hy = finalY[i] * f.multiplier;
            hx = f.x[i];
            renderLine(lx, ly, hx, hy, line, half);
            lx = hx;
            ly = hy;
        }
        if (hx < half) renderLine(hx, hy, half, hy, line, half);
        float[] curve = new float[half];
        for (int i = 0; i < half; i++) curve[i] = INVERSE_DB[Math.max(0, Math.min(255, line[i]))];
        return curve;
    }

    private static int renderPoint(int x0, int y0, int x1, int y1, int x) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        int ady = Math.abs(dy);
        int err = ady * (x - x0);
        int off = err / adx;
        return dy < 0 ? y0 - off : y0 + off;
    }

    private static void renderLine(int x0, int y0, int x1, int y1, int[] v, int limit) {
        int dy = y1 - y0;
        int adx = x1 - x0;
        if (adx <= 0) return;
        int ady = Math.abs(dy);
        int base = dy / adx;
        int sy = dy < 0 ? base - 1 : base + 1;
        int x = x0;
        int y = y0;
        int err = 0;
        ady -= Math.abs(base) * adx;
        if (x < limit) v[x] = y;
        for (x = x0 + 1; x < x1; x++) {
            err += ady;
            if (err >= adx) {
                err -= adx;
                y += sy;
            } else {
                y += base;
            }
            if (x < limit) v[x] = y;
        }
    }

    /** The residue: the detail on top of the floor, in partitions, over up to 8 passes. */
    private static void decodeResidue(Reader r, Residue res, Codebook[] books, float[][] vectors, boolean[] skip, int half) {
        int channels = vectors.length;
        if (res.type == 2) {
            boolean any = false;
            for (boolean s : skip) any |= !s;
            if (!any) return;
            // Type 2: all channels interleaved into one long vector, decoded like type 1
            float[] joined = new float[half * channels];
            decodeVectors(r, res, books, new float[][] {joined}, new boolean[] {false}, half * channels, 1);
            for (int i = 0; i < half; i++) {
                for (int c = 0; c < channels; c++) vectors[c][i] += joined[i * channels + c];
            }
        } else {
            decodeVectors(r, res, books, vectors, skip, half, res.type);
        }
    }

    private static void decodeVectors(Reader r, Residue res, Codebook[] books, float[][] v, boolean[] skip, int size, int type) {
        int begin = Math.min(res.begin, size);
        int end = Math.min(res.end, size);
        int partitions = (end - begin) / res.partitionSize;
        if (partitions <= 0) return;
        Codebook classbook = books[res.classbook];
        int perWord = classbook.dimensions;
        int[][] classes = new int[v.length][partitions + perWord];
        for (int pass = 0; pass < 8; pass++) {
            int count = 0;
            while (count < partitions) {
                if (pass == 0) {
                    for (int c = 0; c < v.length; c++) {
                        if (skip[c]) continue;
                        int temp = classbook.decode(r);
                        if (temp < 0) return;
                        for (int i = perWord - 1; i >= 0; i--) {
                            classes[c][i + count] = temp % res.classifications;
                            temp /= res.classifications;
                        }
                    }
                }
                for (int i = 0; i < perWord && count < partitions; i++, count++) {
                    for (int c = 0; c < v.length; c++) {
                        if (skip[c]) continue;
                        int book = res.books[classes[c][count]][pass];
                        if (book < 0) continue;
                        Codebook b = books[book];
                        int offset = begin + count * res.partitionSize;
                        if (type == 0) {
                            int step = res.partitionSize / b.dimensions;
                            for (int k = 0; k < step; k++) {
                                int e = b.decode(r);
                                if (e < 0) return;
                                for (int j = 0; j < b.dimensions; j++) v[c][offset + k + j * step] += b.vectors[e][j];
                            }
                        } else {
                            int k = 0;
                            while (k < res.partitionSize) {
                                int e = b.decode(r);
                                if (e < 0) return;
                                for (int j = 0; j < b.dimensions && k < res.partitionSize; j++, k++) v[c][offset + k] += b.vectors[e][j];
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Windows and the inverse MDCT ----

    private static float[] window(int n, boolean longBlock, boolean previousLong, boolean nextLong, int shortSize) {
        float[] w = new float[n];
        int leftStart;
        int leftEnd;
        int leftN;
        int rightStart;
        int rightEnd;
        int rightN;
        if (longBlock && !previousLong) {
            leftStart = n / 4 - shortSize / 4;
            leftEnd = n / 4 + shortSize / 4;
            leftN = shortSize / 2;
        } else {
            leftStart = 0;
            leftEnd = n / 2;
            leftN = n / 2;
        }
        if (longBlock && !nextLong) {
            rightStart = n * 3 / 4 - shortSize / 4;
            rightEnd = n * 3 / 4 + shortSize / 4;
            rightN = shortSize / 2;
        } else {
            rightStart = n / 2;
            rightEnd = n;
            rightN = n / 2;
        }
        for (int i = leftStart; i < leftEnd; i++) {
            double s = Math.sin((i - leftStart + 0.5) / leftN * Math.PI / 2);
            w[i] = (float) Math.sin(Math.PI / 2 * s * s);
        }
        for (int i = leftEnd; i < rightStart; i++) w[i] = 1;
        for (int i = rightStart; i < rightEnd; i++) {
            double s = Math.sin((i - rightStart + 0.5) / rightN * Math.PI / 2 + Math.PI / 2);
            w[i] = (float) Math.sin(Math.PI / 2 * s * s);
        }
        return w;
    }

    /**
     * The inverse MDCT: n/2 frequencies into n samples, y[i] = sum of X[k] cos(2 pi / n (i + 1/2 + n/4)(k + 1/2)).
     * It's done as a DCT-IV of n/2 points, which is done with a complex FFT of n/4 points, then unfolded.
     */
    static float[] imdct(float[] x, int n) {
        int m = n / 2;
        double[] c = dct4(x, m);
        float[] y = new float[n];
        for (int i = 0; i < m / 2; i++) y[i] = (float) c[i + m / 2];
        for (int i = m / 2; i < 3 * m / 2; i++) y[i] = (float) -c[3 * m / 2 - 1 - i];
        for (int i = 3 * m / 2; i < n; i++) y[i] = (float) -c[i - 3 * m / 2];
        return y;
    }

    /** c[j] = sum of x[k] cos(pi / m (j + 1/2)(k + 1/2)), through an FFT of m/2 complex points. */
    static double[] dct4(float[] x, int m) {
        int h = m / 2;
        double[] re = new double[h];
        double[] im = new double[h];
        for (int k = 0; k < h; k++) {
            double a = x[2 * k];
            double b = x[m - 1 - 2 * k];
            double angle = -Math.PI * k / m; // the quarter-step goes in the twiddle after the FFT
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            re[k] = a * cos - b * sin;
            im[k] = a * sin + b * cos;
        }
        fft(re, im);
        double[] c = new double[m];
        for (int j = 0; j < h; j++) {
            double angle = -Math.PI * (j + 0.25) / m;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double wr = re[j] * cos - im[j] * sin;
            double wi = re[j] * sin + im[j] * cos;
            c[2 * j] = wr;
            c[m - 1 - 2 * j] = -wi;
        }
        return c;
    }

    /** A plain complex FFT (radix 2, in place), for powers of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }
}
