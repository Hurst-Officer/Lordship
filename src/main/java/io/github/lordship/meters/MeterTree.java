package io.github.lordship.meters;

import java.util.List;

/** Everything about one park's meters: the mains, the submeters, how to read each, and the dated links between them and the lots. */
public record MeterTree(
        List<RateGroup> rateGroups,
        List<MeterMain> mains,
        List<Submeter> submeters,
        List<MeterSetting> settings,
        List<SubmeterMainLink> mainLinks,
        List<SubmeterLotLink> lotLinks
) {}
