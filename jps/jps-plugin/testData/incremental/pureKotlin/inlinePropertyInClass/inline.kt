package inline

class A {
    var z = 0

    inline var f: Int
        get() = z
        set(p: Int) {
            z = p
        }
}