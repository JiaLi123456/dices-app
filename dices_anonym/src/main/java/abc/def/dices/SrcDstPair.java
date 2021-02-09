package abc.def.dices;

import org.onlab.packet.MacAddress;

import java.util.Objects;

public class SrcDstPair {
    final MacAddress src;
    final MacAddress dst;

    public SrcDstPair(MacAddress src, MacAddress dst) {
        this.src = src;
        this.dst = dst;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SrcDstPair that = (SrcDstPair) o;
        return Objects.equals(src, that.src) &&
                Objects.equals(dst, that.dst);
    }

    @Override
    public int hashCode() {
        return Objects.hash(src, dst);
    }
}
