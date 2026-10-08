package com.spendwise.app.core.merchant

/**
 * Shipped merchant → category dictionary (ADR-001 fallback, used only when no learned rule exists).
 *
 * Matching is by whole words on the normalised merchant name, so "OLA" never matches "COCA COLA" and "LAB" never
 * matches "LABEL". Priority, highest first:
 *  1. Named merchants/brands ("DMart", "Apollo Pharmacy", "Bata") — written with capitals in [DATA].
 *  2. Multi-word keywords ("medical store", "grocery store").
 *  3. Single keywords ("pharmacy", "shoe").
 *  4. Weak, generic words ("store", "shop", "hotel", "fresh") — only when nothing more specific matched.
 * Within a tier the longer phrase wins. Payment apps (Google Pay, PhonePe, Paytm…) carry no category: paying
 * "via PhonePe" says nothing about what was bought, so those stay uncategorised for the user to decide.
 */
object MerchantDictionary {
    const val FOOD = 10L
    const val GROCERIES = 11L
    const val SHOPPING = 12L
    const val BILLS = 13L
    const val TRANSPORT = 14L
    const val FUEL = 15L
    const val ENTERTAINMENT = 16L
    const val SUBSCRIPTIONS = 17L
    const val EDUCATION = 18L
    const val HEALTH = 19L
    const val TRAVEL = 20L
    const val HOUSEHOLD = 21L
    const val PERSONAL_CARE = 22L
    const val INSURANCE = 25L
    const val CLOTHING = 28L
    const val FOOTWEAR = 29L
    const val ELECTRONICS = 30L
    const val FITNESS = 31L
    const val JEWELLERY = 32L
    const val INVESTMENT = 33L

    /** Names in [DATA] → seeded category IDs (DatabaseSetup). "Payments" deliberately maps to no category. */
    private val CATEGORY = mapOf(
        "Groceries" to GROCERIES, "Food" to FOOD, "Shopping" to SHOPPING, "Clothing" to CLOTHING, "Footwear" to FOOTWEAR,
        "Education" to EDUCATION, "Electronics" to ELECTRONICS, "Medical" to HEALTH, "Transport" to TRANSPORT,
        "Fuel" to FUEL, "Bills" to BILLS, "Subscriptions" to SUBSCRIPTIONS, "Entertainment" to ENTERTAINMENT,
        "Fitness" to FITNESS, "Personal Care" to PERSONAL_CARE, "Jewellery" to JEWELLERY, "Travel" to TRAVEL,
        "Home" to HOUSEHOLD, "Investment" to INVESTMENT, "Insurance" to INSURANCE, "Payments" to NO_CATEGORY,
    )

    /** Generic words that only apply when nothing more specific matched (and ambiguous short brand names). */
    private val WEAK = setOf(
        "STORE", "SHOP", "SHOPPING", "HOTEL", "HOTELS", "BOOKING", "EVENT", "EVENTS", "RIDE", "RIDES", "FRESH", "POWER",
        "ORGANIC", "KITCHEN", "CENTRAL", "LIFESTYLE", "ARROW", "CAMPUS", "SHELL", "AHA", "VI", "GOLD", "SILVER", "AUTO",
        "WATER", "ELECTRIC", "TABLE", "CHAIR", "BED", "PEN", "LAB", "GAME", "PUMP", "BUNK", "TEA", "MESS", "PREMIUM",
        "APPLE", "MOCHI", "DURIAN", "CLARKS", "CENTRE",
    )

    private class Entry(val words: List<String>, val compact: String, val categoryId: Long, val tier: Int)
    private class Hit(val categoryId: Long, val tier: Int, val length: Int)

    private val ENTRIES: List<Entry> by lazy {
        DATA.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "→" in it }
            .mapNotNull { line ->
                val phrase = line.substringBefore("→").trim()
                val category = CATEGORY[line.substringAfter("→").trim()] ?: return@mapNotNull null
                val words = words(phrase)
                if (words.isEmpty()) return@mapNotNull null
                val compact = words.joinToString("")
                val isBrand = phrase.any { it.isUpperCase() }
                val tier = when {
                    compact in WEAK -> 0
                    isBrand -> 3
                    words.size > 1 -> 2
                    else -> 1
                }
                Entry(words, compact, category, tier)
            }
            .toList()
    }

    /** Normalised words: upper case, apostrophes dropped ("Domino's" → DOMINOS), & → AND, + → PLUS. */
    internal fun words(text: String): List<String> {
        var s = text.uppercase().replace("'", "").replace("’", "").replace("&", " AND ").replace("+", " PLUS ")
        if ('@' in s && ' ' !in s.trim()) s = s.substringBefore('@') // a VPA: "swiggy.food@axisbank" → SWIGGY FOOD
        return s.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
    }

    /** Best category for a merchant, or null when unknown (or when it's only a payment app). */
    fun categoryFor(merchantKey: String?): Long? {
        val tokens = words(merchantKey ?: return null)
        if (tokens.isEmpty()) return null
        var best: Hit? = null
        for (e in ENTRIES) {
            val hit = match(tokens, e) ?: continue
            val b = best
            if (b == null || hit.tier > b.tier || (hit.tier == b.tier && hit.length > b.length)) best = hit
        }
        return best?.categoryId?.takeIf { it != NO_CATEGORY }
    }

    private fun match(tokens: List<String>, e: Entry): Hit? {
        // Exact words in sequence: "PIZZA HUT", or singular keyword vs plural word ("MEDICALS").
        for (i in 0..tokens.size - e.words.size) {
            var ok = true
            for (j in e.words.indices) {
                val t = tokens[i + j]
                val w = e.words[j]
                if (t != w && !(e.tier in 1..2 && t == w + "S")) {
                    ok = false
                    break
                }
            }
            if (ok) return Hit(e.categoryId, e.tier, e.compact.length)
        }
        // Spacing differences: "BIG BASKET" vs "BigBasket", "MAKE MY TRIP" vs "MakeMyTrip".
        for (i in tokens.indices) {
            val sb = StringBuilder()
            for (j in i until minOf(tokens.size, i + 4)) {
                sb.append(tokens[j])
                if (j > i && sb.toString() == e.compact) return Hit(e.categoryId, e.tier, e.compact.length)
            }
        }
        // A brand glued to more text ("BLINKITCOMMERCE", "ZOMATOLTD") — long brand names only.
        if (e.tier == 3 && e.compact.length >= 6 && tokens.any { it.length > e.compact.length && it.startsWith(e.compact) }) {
            return Hit(e.categoryId, e.tier, e.compact.length - 1)
        }
        return null
    }

    private const val NO_CATEGORY = -1L

    // The user's list (2026-10-08), plus a few earlier entries it didn't cover (# EXTRA).
    private val DATA = """
# GROCERIES
grocery → Groceries
groceries → Groceries
mart → Groceries
supermarket → Groceries
super mart → Groceries
kirana → Groceries
kiranam → Groceries
provision → Groceries
provision store → Groceries
general store → Groceries
departmental store → Groceries
vegetable → Groceries
vegetables → Groceries
fruit → Groceries
fruits → Groceries
organic → Groceries
fresh → Groceries
ration → Groceries
grocery store → Groceries
grocery shop → Groceries
DMart → Groceries
Blinkit → Groceries
Zepto → Groceries
Instamart → Groceries
Swiggy Instamart → Groceries
BigBasket → Groceries
BB Now → Groceries
JioMart → Groceries
Reliance Smart → Groceries
More Supermarket → Groceries
Spencer's → Groceries
Star Bazaar → Groceries
Nature's Basket → Groceries
Ratnadeep → Groceries
Nilgiris → Groceries
Heritage Fresh → Groceries
Foodhall → Groceries

# FOOD
food → Food
foods → Food
restaurant → Food
restaurants → Food
cafe → Food
coffee → Food
tea → Food
bakery → Food
bakes → Food
biryani → Food
pizza → Food
burger → Food
sandwich → Food
snacks → Food
sweets → Food
mithai → Food
juice → Food
tiffin → Food
canteen → Food
mess → Food
kitchen → Food
dining → Food
eatery → Food
hotel → Food
Swiggy → Food
Zomato → Food
EatClub → Food
Domino's → Food
Pizza Hut → Food
McDonald's → Food
KFC → Food
Burger King → Food
Subway → Food
Starbucks → Food
Dunkin' → Food
Barista → Food
Cafe Coffee Day → Food
Chaayos → Food
Wow! Momo → Food
Biryani Blues → Food
Behrouz Biryani → Food
Faasos → Food
OvenStory → Food
The Good Bowl → Food
FreshMenu → Food
Box8 → Food
EatSure → Food
Chai Point → Food
Third Wave Coffee → Food
Blue Tokai → Food
Mio Amore → Food
Monginis → Food
Sangeetha → Food
Saravana Bhavan → Food
Anjappar → Food
A2B → Food
Adyar Ananda Bhavan → Food

# SHOPPING
shopping → Shopping
shop → Shopping
store → Shopping
Amazon → Shopping
Flipkart → Shopping
Myntra → Shopping
AJIO → Shopping
Meesho → Shopping
Snapdeal → Shopping
Tata CLiQ → Shopping
Tata Neu → Shopping
Shoppers Stop → Shopping
Lifestyle → Shopping
Westside → Shopping
Pantaloons → Shopping
Max Fashion → Shopping
Reliance Trends → Shopping
Central → Shopping
Fashion Factory → Shopping

# CLOTHING / FASHION
fashion → Clothing
fashions → Clothing
clothing → Clothing
clothes → Clothing
garment → Clothing
garments → Clothing
apparel → Clothing
dress → Clothing
dresses → Clothing
menswear → Clothing
womenswear → Clothing
kidswear → Clothing
textile → Clothing
textiles → Clothing
boutique → Clothing
saree → Clothing
sarees → Clothing
uniform → Clothing
readymade → Clothing
H&M → Clothing
Zara → Clothing
Uniqlo → Clothing
Levi's → Clothing
Allen Solly → Clothing
Peter England → Clothing
Van Heusen → Clothing
Louis Philippe → Clothing
Arrow → Clothing
Raymond → Clothing
Manyavar → Clothing
Fabindia → Clothing
Biba → Clothing
W for Woman → Clothing
Aurelia → Clothing

# FOOTWEAR
shoe → Footwear
shoes → Footwear
footwear → Footwear
foot wear → Footwear
sandal → Footwear
sandals → Footwear
slipper → Footwear
slippers → Footwear
chappal → Footwear
chappals → Footwear
sneaker → Footwear
sneakers → Footwear
Bata → Footwear
Metro Shoes → Footwear
Mochi → Footwear
Relaxo → Footwear
Sparx → Footwear
Campus → Footwear
Woodland → Footwear
Crocs → Footwear
Skechers → Footwear
Clarks → Footwear

# EDUCATION / STATIONERY
stationery → Education
stationary → Education
book → Education
books → Education
notebook → Education
notebooks → Education
pen → Education
pencil → Education
xerox → Education
photocopy → Education
printing → Education
school → Education
college → Education
university → Education
tuition → Education
coaching → Education
academy → Education
institute → Education
course → Education
courses → Education
training → Education
exam → Education
education → Education
Udemy → Education
Coursera → Education
Unacademy → Education
Physics Wallah → Education
BYJU'S → Education
Vedantu → Education
upGrad → Education
Simplilearn → Education
Great Learning → Education
Scaler → Education
Chegg → Education

# ELECTRONICS
electronics → Electronics
electronic → Electronics
mobile → Electronics
phone → Electronics
smartphone → Electronics
computer → Electronics
laptop → Electronics
printer → Electronics
camera → Electronics
television → Electronics
tv → Electronics
appliance → Electronics
electrical → Electronics
Croma → Electronics
Reliance Digital → Electronics
Vijay Sales → Electronics
Sangeetha Mobiles → Electronics
Poorvika → Electronics
Sathya → Electronics
Bajaj Electronics → Electronics
Lot Mobiles → Electronics
Samsung → Electronics
Apple → Electronics
OnePlus → Electronics
Xiaomi → Electronics
realme → Electronics
Vivo → Electronics
OPPO → Electronics
Dell → Electronics
HP → Electronics
Lenovo → Electronics

# MEDICAL / HEALTHCARE
pharmacy → Medical
medical → Medical
medicine → Medical
medicines → Medical
chemist → Medical
pharma → Medical
clinic → Medical
hospital → Medical
doctor → Medical
dental → Medical
dentist → Medical
diagnostic → Medical
diagnostics → Medical
laboratory → Medical
lab → Medical
pathology → Medical
healthcare → Medical
Apollo Pharmacy → Medical
MedPlus → Medical
Tata 1mg → Medical
PharmEasy → Medical
Netmeds → Medical
Practo → Medical
HealthKart → Medical
Wellness Forever → Medical
Guardian Pharmacy → Medical
Narayana Health → Medical
Apollo Hospitals → Medical
Fortis → Medical
Max Healthcare → Medical
Manipal Hospitals → Medical
Kauvery Hospital → Medical

# TRANSPORT
transport → Transport
taxi → Transport
cab → Transport
auto → Transport
autorickshaw → Transport
bus → Transport
metro → Transport
train → Transport
railway → Transport
parking → Transport
toll → Transport
fastag → Transport
ride → Transport
rides → Transport
Uber → Transport
Ola → Transport
Rapido → Transport
Namma Yatri → Transport
RedBus → Transport
AbhiBus → Transport
IRCTC → Transport
Chennai Metro → Transport
Delhi Metro → Transport
Bangalore Metro → Transport
Mumbai Metro → Transport
Park+ → Transport

# FUEL
petrol → Fuel
diesel → Fuel
fuel → Fuel
petroleum → Fuel
bunk → Fuel
pump → Fuel
fuel station → Fuel
petrol pump → Fuel
Indian Oil → Fuel
IOCL → Fuel
Bharat Petroleum → Fuel
BPCL → Fuel
Hindustan Petroleum → Fuel
HPCL → Fuel
Shell → Fuel
Nayara Energy → Fuel
Reliance Petroleum → Fuel
Essar → Fuel

# BILLS / UTILITIES / TELECOM
recharge → Bills
prepaid → Bills
postpaid → Bills
airtel → Bills
jio → Bills
vodafone → Bills
vi → Bills
bsnl → Bills
telecom → Bills
internet → Bills
broadband → Bills
wifi → Bills
fiber → Bills
fibre → Bills
electricity → Bills
electric → Bills
water → Bills
gas → Bills
lpg → Bills
cylinder → Bills
power → Bills
electricity bill → Bills
water bill → Bills
gas bill → Bills
mobile recharge → Bills
mobile bill → Bills
phone bill → Bills
ACT Fibernet → Bills
Hathway → Bills
Tata Play → Bills
Dish TV → Bills
Airtel Xstream → Bills
JioFiber → Bills
Jio AirFiber → Bills

# SUBSCRIPTIONS
subscription → Subscriptions
subscriptions → Subscriptions
Netflix → Subscriptions
Spotify → Subscriptions
YouTube Premium → Subscriptions
YouTube Music → Subscriptions
Amazon Prime → Subscriptions
Prime Video → Subscriptions
JioHotstar → Subscriptions
Hotstar → Subscriptions
SonyLIV → Subscriptions
ZEE5 → Subscriptions
Aha → Subscriptions
Apple Music → Subscriptions
Apple TV+ → Subscriptions
Disney+ → Subscriptions
Gaana → Subscriptions

# ENTERTAINMENT
movie → Entertainment
movies → Entertainment
cinema → Entertainment
theatre → Entertainment
theater → Entertainment
concert → Entertainment
event → Entertainment
events → Entertainment
gaming → Entertainment
game → Entertainment
games → Entertainment
PVR → Entertainment
INOX → Entertainment
PVR INOX → Entertainment
BookMyShow → Entertainment
Paytm Movies → Entertainment
Sony Pictures → Entertainment

# FITNESS
gym → Fitness
fitness → Fitness
yoga → Fitness
zumba → Fitness
crossfit → Fitness
sports club → Fitness
health club → Fitness

# PERSONAL CARE
salon → Personal Care
saloon → Personal Care
barber → Personal Care
barbershop → Personal Care
haircut → Personal Care
hair salon → Personal Care
spa → Personal Care
beauty → Personal Care
grooming → Personal Care
parlour → Personal Care
parlor → Personal Care
cosmetic → Personal Care
cosmetics → Personal Care
makeup → Personal Care
skincare → Personal Care
skin care → Personal Care
hair care → Personal Care
perfume → Personal Care
perfumes → Personal Care
fragrance → Personal Care
lotion → Personal Care
Nykaa → Personal Care
Nykaa Man → Personal Care
Purplle → Personal Care
Sephora → Personal Care
Health & Glow → Personal Care
Lakme Salon → Personal Care
Naturals Salon → Personal Care
Jawed Habib → Personal Care
Enrich Salon → Personal Care
Looks Salon → Personal Care

# JEWELLERY
jewellery → Jewellery
jewelry → Jewellery
gold → Jewellery
silver → Jewellery
diamond → Jewellery
diamonds → Jewellery
ornament → Jewellery
ornaments → Jewellery
Tanishq → Jewellery
Kalyan Jewellers → Jewellery
Malabar Gold → Jewellery
Joyalukkas → Jewellery
Senco Gold → Jewellery
CaratLane → Jewellery
BlueStone → Jewellery
TBZ → Jewellery
Reliance Jewels → Jewellery

# TRAVEL
travel → Travel
tour → Travel
tourism → Travel
holiday → Travel
flight → Travel
airlines → Travel
airways → Travel
airport → Travel
hotels → Travel
resort → Travel
resorts → Travel
booking → Travel
Airbnb → Travel
OYO → Travel
MakeMyTrip → Travel
Goibibo → Travel
Cleartrip → Travel
EaseMyTrip → Travel
Yatra → Travel
ixigo → Travel
Booking.com → Travel
Agoda → Travel
Trivago → Travel
IndiGo → Travel
Air India → Travel
Air India Express → Travel
Akasa Air → Travel
SpiceJet → Travel
Alliance Air → Travel
Vistara → Travel

# HOME / FURNITURE
furniture → Home
sofa → Home
bed → Home
table → Home
chair → Home
mattress → Home
interior → Home
interiors → Home
furnishing → Home
hardware → Home
utensils → Home
kitchenware → Home
home decor → Home
home needs → Home
IKEA → Home
Pepperfry → Home
Urban Ladder → Home
Wakefit → Home
Home Centre → Home
Godrej Interio → Home
Nilkamal → Home
Durian → Home
SleepyCat → Home

# INVESTMENT
investment → Investment
investments → Investment
mutual fund → Investment
mutual funds → Investment
sip → Investment
stocks → Investment
stock → Investment
shares → Investment
demat → Investment
brokerage → Investment
Zerodha → Investment
Groww → Investment
Upstox → Investment
Angel One → Investment
Paytm Money → Investment
ICICI Direct → Investment
HDFC Securities → Investment
Kotak Securities → Investment
Motilal Oswal → Investment
5paisa → Investment

# INSURANCE
insurance → Insurance
premium → Insurance
LIC → Insurance
HDFC Life → Insurance
ICICI Prudential → Insurance
SBI Life → Insurance
Max Life → Insurance
Tata AIA → Insurance
Bajaj Allianz → Insurance
Star Health → Insurance
Niva Bupa → Insurance
Care Health → Insurance

# PAYMENT PLATFORMS (no category: the payment app doesn't say what was bought)
Google Pay → Payments
GPay → Payments
PhonePe → Payments
Paytm → Payments
Amazon Pay → Payments
BHIM → Payments
Mobikwik → Payments
Freecharge → Payments

# EXTRA (earlier dictionary entries the list above doesn't cover)
Hungerbox → Food
Decathlon → Shopping
TNEB → Bills
TANGEDCO → Bills
BESCOM → Bills
Google Play → Subscriptions
YouTube → Subscriptions
1mg → Medical
Reliance Fresh → Groceries
More Retail → Groceries
"""
}
