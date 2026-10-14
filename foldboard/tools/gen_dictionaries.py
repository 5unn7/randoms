#!/usr/bin/env python3
"""Generate FoldBoard word-frequency dictionaries from the `wordfreq` package.

Output: app/src/main/assets/dict/<lang>.txt, one `word<TAB>zipf*100` per line,
sorted by frequency. Data: wordfreq (Apache-2.0 code, CC BY-SA 4.0 data).

    pip install wordfreq==3.1.1
    python3 tools/gen_dictionaries.py
"""
import os
import re
from wordfreq import top_n_list, zipf_frequency

LANGS = {"en": 60000, "es": 40000, "fr": 40000, "de": 40000, "it": 35000, "pt": 35000}
WORD = re.compile(r"^[^\W\d_]+(['’-][^\W\d_]+)*$")
EN_CAPS = {"i": "I", "i'm": "I'm", "i'll": "I'll", "i've": "I've", "i'd": "I'd"}
SINGLE_OK = {"en": {"a", "i"}, "es": {"a", "y", "o", "e", "u"}, "fr": {"a", "y", "à"},
             "de": set(), "it": {"a", "e", "o", "è", "i"}, "pt": {"a", "e", "o", "é", "à"}}

# Web text is full of typos ("teh", "dont", "recieve") that would otherwise count as valid words
# and stop autocorrect from fixing them. Drop rare words that are one transposition, one doubled
# letter, or a missing apostrophe away from a much more common word, plus well-known misspellings.
EN_KEEP = {"ill", "wed", "id", "its", "lets", "shed", "well", "were", "hell", "shell", "hes", "whos", "abel"}
EN_MISSPELLINGS = set("""
teh hte adn taht yuo jsut waht knwo becasue alot definately seperate tommorow tomorow accomodate wich untill recieve beleive occurence goverment
enviroment wierd thier freind truely arguement calender existance independant neccessary noticable
publically reccomend refered sucess tounge begining bizzare comming concious embarass finaly foriegn
gaurd happend immediatly knowlege libary mispell occassion persue posession prefered realy relevent
resturant rythm sieze suprise tatoo threshhold vaccuum acheive adress agressive apparantly basicly
beggining belive buisness carribean cemetary collegue comittee completly concensus copywrite curiousity
dilema dissapoint dissapear ecstacy embarassed excercise experiance familar fourty futher gratefull
greatful guage harrass hieght hygene ignorence imediately incidently intresting irrelevent jewelery
liason lisence maintainance manuever millenium miniscule mischievious momento neice nieghbor
noone occured offical oppurtunity orignal paralell particulary pavillion peice percieve persistant
politican posible potatos preceed presance privelege probly pronounciation questionaire recomend
remeber repitition rember sargent shedule similiar sincerly speach strenght succesful supposably
temperture tendancy therefor tomatoe tommorrow tounge truley twelth tyrany underate vacume vegatable
wanna gonna gotta wether whereever wierdo writting yeild
""".split())

def drop_misspellings(rows, lang):
    freq = {w: z for w, z in rows}
    out = []
    for w, z in rows:
        lw = w.lower()
        if lang == "en" and lw in EN_MISSPELLINGS:
            continue
        drop = False
        if z < 330 and not (lang == "en" and lw in EN_KEEP):
            swaps, doubles = set(), set()
            for i in range(len(lw) - 1):  # transpositions
                swaps.add(lw[:i] + lw[i + 1] + lw[i] + lw[i + 2:])
            for i in range(len(lw)):  # doubled / undoubled letters
                doubles.add(lw[:i] + lw[i] + lw[i:])
                if i + 1 < len(lw) and lw[i] == lw[i + 1]:
                    doubles.add(lw[:i] + lw[i + 1:])
            swaps.discard(lw)
            doubles.discard(lw)
            f = lambda c: freq.get(c, freq.get(c.capitalize(), 0))
            drop = (z < 300 and any(f(c) - z >= 230 for c in swaps)) or any(f(c) - z >= 180 for c in doubles)
        if lang == "en" and not drop and "'" not in lw and lw not in EN_KEEP:
            for c in (lw[:i] + "'" + lw[i:] for i in range(1, len(lw))):
                f = freq.get(c, freq.get(c[0].upper() + c[1:], 0))
                if f - z >= 80:
                    drop = True
                    break
        if not drop:
            out.append((w, z))
    return out


out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dict")
os.makedirs(out_dir, exist_ok=True)
for lang, n in LANGS.items():
    rows = []
    for w in top_n_list(lang, int(n * 1.6)):
        w = w.replace("’", "'")
        if not WORD.match(w):
            continue
        if len(w) == 1 and w not in SINGLE_OK[lang]:
            continue
        z = zipf_frequency(w, lang)
        if "'" in w and z < 3.0:
            continue
        if lang == "en":
            w = EN_CAPS.get(w, w)
        rows.append((w, int(round(z * 100))))
    rows = drop_misspellings(rows, lang)[:n]
    with open(os.path.join(out_dir, f"{lang}.txt"), "w", encoding="utf-8") as f:
        for w, z in rows:
            f.write(f"{w}\t{z}\n")
    print(lang, len(rows))
