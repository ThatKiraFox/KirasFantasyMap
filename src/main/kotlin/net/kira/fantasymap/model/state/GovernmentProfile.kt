package net.kira.fantasymap.model.state

class GovernmentProfile(
    id: String,
    representative: Representative,

) {
}

class Representative(
    representative: HeadsOfState,
    electionMethod: HOSelection
)

enum class HeadsOfState {
    MONARCH,
    PRESIDENT,
    COUNCIL,
    RELIGIOUS_LEADER
}

enum class HOSelection {
    INHERITANCE,
    POPULAR_VOTE,
    FORCE,
    THE_RULING_PARTY,
    SMALL_GROUP,
    LEGISLATURE
}

