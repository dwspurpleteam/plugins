package ru.itmo.plugins.attendance.service.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Friendly guest names, with enough of their identity suffix to keep different participants distinct. */
final class ParticipantLabels {
    private static final Pattern GUEST_IDENTITY = Pattern.compile("^([a-z0-9_]+)-([0-9a-z]{15})$");

    private record Name(String text, String tag, boolean generic) { }

    private ParticipantLabels() { }

    static Map<String, String> forIdentities(Collection<String> identities) {
        var names = new HashMap<String, Name>();
        var groups = new HashMap<String, List<String>>();
        for (String identity : identities) {
            Name name = nameOf(identity);
            names.put(identity, name);
            groups.computeIfAbsent(name.text(), ignored -> new ArrayList<>()).add(identity);
        }
        var labels = new HashMap<String, String>();
        names.forEach((identity, name) -> {
            List<String> group = groups.get(name.text());
            if (name.tag() == null || (!name.generic() && group.size() == 1)) {
                labels.put(identity, name.text());
                return;
            }
            for (int length = 4; length <= name.tag().length(); length++) {
                String prefix = name.tag().substring(0, length);
                boolean unique = group.stream().filter(other -> !other.equals(identity)).map(names::get)
                        .noneMatch(other -> other.tag() != null && other.tag().startsWith(prefix));
                if (unique) {
                    labels.put(identity, name.text() + " #" + prefix);
                    return;
                }
            }
            labels.put(identity, identity);
        });
        // A raw identity can itself look like a formatted guest name. Fall back to raw IDs for such collisions.
        boolean changed;
        do {
            changed = false;
            var used = new HashMap<String, List<String>>();
            labels.forEach((identity, label) -> used.computeIfAbsent(label, ignored -> new ArrayList<>()).add(identity));
            for (var group : used.values()) {
                if (group.size() > 1) {
                    for (String identity : group) {
                        if (!identity.equals(labels.get(identity))) {
                            labels.put(identity, identity);
                            changed = true;
                        }
                    }
                }
            }
        } while (changed);
        return Map.copyOf(labels);
    }

    private static Name nameOf(String identity) {
        var match = GUEST_IDENTITY.matcher(identity);
        if (!match.matches()) return new Name(identity, null, false);
        var words = new ArrayList<String>();
        for (String word : match.group(1).split("_")) {
            if (!word.isEmpty()) words.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        boolean generic = match.group(1).equals("participant") || words.isEmpty();
        return new Name(generic ? "Участник" : String.join(" ", words), match.group(2), generic);
    }
}
