package io.nekohasekai.sagernet.bg.root
fun main() {
    var count=0
    fun checkCase(ok:Boolean){check(ok);count++}
    fun plan(s:Set<String> = setOf("ap0", "rndis0"), active:Set<String> = setOf("ap0", "rndis0"), up:Set<String> = setOf("rmnet0"), ignored:Set<String> = emptySet(), port:Int=15353, occupied:Set<Int> = emptySet(), v6:Boolean=true) = HevGatewayDnsPlan.build(s,active,up,ignored,port,occupied,v6)
    val p=plan()!!
    checkCase(p.rules.size==20)
    checkCase(p.rules.count{it.table=="nat"}==8)
    checkCase(p.rules.count{it.args.last()=="ACCEPT"}==8)
    checkCase(p.rules.count{it.args.last()=="DROP"}==4)
    checkCase(p.rules.filter{it.table=="nat"}.all{it.args.containsAll(listOf("LOCAL","53","REDIRECT","15353"))})
    checkCase(p.rules.filter{it.args.last()=="ACCEPT"}.all{it.args.containsAll(listOf("DNAT","ORIGINAL","--ctorigdstport","53"))})
    checkCase(plan(v6=false)!!.rules.all{it.family==4})
    checkCase(plan(s=setOf("ap+"),active=setOf("ap+"))==null)
    checkCase(plan(s=setOf("lo"),active=setOf("lo"))==null)
    checkCase(plan(s=setOf("hevtun0"),active=setOf("hevtun0"))==null)
    checkCase(plan(s=setOf("ap0"),up=setOf("ap0"))==null)
    checkCase(plan(s=setOf("ap0"),ignored=setOf("ap0"))==null)
    checkCase(plan(s=setOf("ap0"),active=emptySet())==null)
    checkCase(plan(port=53)==null)
    checkCase(plan(port=65536)==null)
    checkCase(plan(occupied=setOf(15353))==null)
    checkCase(plan(s=emptySet())!!.rules.all{it.args.last()=="DROP"})
    checkCase(plan(s=(1..17).map{"ap$it"}.toSet(),active=(1..17).map{"ap$it"}.toSet())==null)
    println("PASS $count/$count")
}
