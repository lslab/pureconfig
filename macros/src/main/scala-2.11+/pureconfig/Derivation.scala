package pureconfig

import scala.collection.mutable
import scala.language.experimental.macros
import scala.reflect.macros.whitebox

import pureconfig.derivation._

sealed trait Derivation[A] {
  def value: A
}

object Derivation {

  // A derivation for which an implicit value of `A` could be found.
  case class Successful[A](value: A) extends Derivation[A]

  // A derivation for which an implicit `A` could be found. This is only used internally by the `materializeDerivation`
  // macro - when a derivation requested directly by a user is successfully materialized, it is guaranteed to be a
  // `Derivation.Successful`.
  case class Failed[A]() extends Derivation[A] {
    def value = throw new IllegalStateException("Illegal Derivation")
  }

  implicit def materializeDerivation[A]: Derivation[A] = macro DerivationMacros.materializeDerivation[A]
}

@macrocompat.bundle
class DerivationMacros(val c: whitebox.Context) extends LazyContextParser with MacroCompat {
  import c.universe._

  private[this] implicit class RichType(val t: Type) {
    def toTree: Tree =
      if (t.typeArgs.isEmpty) tq"${t.typeSymbol}"
      else tq"${t.typeConstructor.toTree}[..${t.typeArgs.map(_.toTree)}]"
  }

  private[this] implicit class RichTree(val t: Tree) {
    def toType: Type = c.typecheck(t, c.TYPEmode).tpe
  }

  // The entrypoint for materializing `Derivation` instances.
  def materializeDerivation[A: WeakTypeTag]: Tree = {

    // check if the `-Xmacro-settings:materialize-derivations` scalac flag is enabled
    val isDerivationEnabled = c.settings.contains("materialize-derivations")

    // check if the first implicit in the chain is a `Derivation` (if it isn't, not only we can't show custom messages
    // but we may be unable to parse `Lazy` trees)
    val isHeadImplicitADerivation = c.openImplicits.headOption.exists(_.pre =:= typeOf[Derivation.type])

    // check the `-Xmacro-settings:materialize-derivations` scalac flag
    if (!isDerivationEnabled || !isHeadImplicitADerivation) {
      // when not present, simply render the base `Derivation` constructor with `implcitly[A]`. This results in the same
      // behavior as without `Derivation` (apart from the extra wrapper).
      q"_root_.pureconfig.Derivation.Successful(implicitly[${weakTypeOf[A]}])"

    } else {
      // if `isRootDerivation` is `false`, then this is a `Derivation` triggered inside another `Derivation`
      val isRootDerivation = c.openImplicits.count(_.pre =:= typeOf[Derivation.type]) == 1

      if (isRootDerivation) materializeRootDerivation(weakTypeOf[A])
      else materializeInnerDerivation(weakTypeOf[A])
    }
  }

  // Materialize a `Derivation` that was requested in the context of another.
  //
  // In this case a `Derivation` instance is always materialized so that the implicit search does not stop here. We
  // materialize a `Derivation.failed` for missing implicits so that the `Derivation` at the root level can easily find
  // and handle them.
  private[this] def materializeInnerDerivation(typ: Type): Tree = {
    inferImplicitValueCompat(typ) match {
      case EmptyTree => q"_root_.pureconfig.Derivation.Failed[$typ]()"
      case value => q"_root_.pureconfig.Derivation.Successful[$typ]($value)"
    }
  }

  // Materialize a `Derivation` at the root level, i.e. not caused by the materialization of another `Derivation`.
  //
  // As usual, it searches for an implicit for `A`. If it fails, the macro aborts as expected, causing an implicit not
  // found error with a basic message. If an implicit is found, it still needs to search the tree found in order to
  // check if some inner derivations materialized a `Derivation.failed`. If that's the case, it collects those failures
  // and prints a nice message.
  private[this] def materializeRootDerivation(typ: Type): Tree = {
    inferImplicitValueCompat(typ) match {
      case EmptyTree =>
        // failed to find an implicit at the root level of the derivation; set a generic implicitNotFound message
        // without further information
        setImplicitNotFound(typ, Nil)

        // cause the implicit to fail materializing - the message is ignored
        c.abort(c.enclosingPosition, "")

      case value =>
        // collect the failed derivations in the built implicit tree
        val failed = collectFailedDerivations(value)

        if (failed.isEmpty) {
          q"_root_.pureconfig.Derivation.Successful[$typ]($value)"
        } else {
          // if there are failures, that means one of the inner implicits was not found - set a message with details
          // about the paths failed
          setImplicitNotFound(typ, failed)

          // cause the implicit to fail materializing - the message is ignored
          c.abort(c.enclosingPosition, "")
        }
    }
  }

  // Traverses a tree and collects a list of paths from the root derivation to the failed leaves.
  private[this] def collectFailedDerivations(tree: Tree): List[List[Type]] = {
    val failures = mutable.ListBuffer[List[Type]]()

    // Define a Traverser for a DFS on the AST. In "regular" derivations, the `Derivation.Failed` tress (if any) would
    // be nested in layers of `Derivation.Successful` calls, so it would be trivial to extract the failing paths from
    // there. However, `Lazy` flattens the structure of the tree, so special handling of `Lazy` trees is needed. The
    // logic for that is in `LazyContextParser`.
    val traverser = new Traverser {
      private[this] var currentPath = List[Type]()
      private[this] var lazyCtxOpt = Option.empty[LazyContext]

      override def traverse(tree: Tree) = tree match {
        case q"pureconfig.Derivation.Successful.apply[$typ]($valueExpr)" =>
          currentPath = typ.toType :: currentPath
          traverse(valueExpr)
          currentPath = currentPath.tail

        case q"pureconfig.Derivation.Failed.apply[$typ]()" =>
          failures += typ.toType :: currentPath

        case LazyContextTree(lazyCtx) =>
          // if the `Lazy` context tree is found, store the created `LazyContext` instance and continue traversing from
          // the `Lazy` entrypoint.
          lazyCtxOpt = Some(lazyCtx)
          traverse(lazyCtx.entrypoint)
          lazyCtxOpt = None

        case _ =>
          // for every other tree, check if it is a reference to a lazy implicit. If so, follow the reference and
          // continue traversing the corresponding tree with the new `LazyContext`.
          lazyCtxOpt.flatMap(_.followRef(tree)) match {
            case None => super.traverse(tree)

            case Some((newLazyCtx, lazyTree)) =>
              val oldLazyCtx = lazyCtxOpt
              lazyCtxOpt = Some(newLazyCtx)
              traverse(lazyTree)
              lazyCtxOpt = oldLazyCtx
          }
      }
    }

    traverser.traverse(tree)
    failures.toList.map(_.reverse)
  }

  // Prepares and sets the message to be printed for the given failed derivations.
  private[this] def setImplicitNotFound(typ: Type, failedDerivations: List[List[Type]]): Unit = {
    val builder = new StringBuilder()

    failedDerivations match {
      case Nil => builder ++= s"could not find ${prettyPrintType(typ)}\n"
      case _ => builder ++= s"could not derive ${prettyPrintType(typ)}, because:\n"
    }

    def buildMessage(scopedFailedDerivations: List[List[Type]], depth: Int): Unit = {

      // the distinct first derivation steps that failed
      val distinctDirectReasons: List[Type] =
        scopedFailedDerivations.collect { case typ :: _ => typ }.distinct

      // a list of pairs associating each first derivation step with the list of paths scoped to that step
      val failuresByDirectReason: List[(Type, List[List[Type]])] =
        distinctDirectReasons.map { typ =>
          typ -> scopedFailedDerivations.collect {
            // collect only paths that start with `typ` and do not become empty after scoping it
            case `typ` :: resth :: restl => resth :: restl
          }
        }

      failuresByDirectReason.foreach {
        case (typ, innerReasons) =>
          val msgEnding = if (innerReasons.isEmpty) "" else ", because:"
          builder ++= s"${"  " * depth}- missing ${prettyPrintType(typ)}$msgEnding\n"
          buildMessage(innerReasons, depth + 1)
      }
    }

    buildMessage(failedDerivations, 1)
    setImplicitNotFound(builder.toString)
  }

  private[this] def prettyPrintType(typ: Type): String =
    prettyPrintType(typ.toTree)

  private[this] def prettyPrintType(typ: Tree): String = typ match {
    case tq"Lazy[$lzyArg]" => prettyPrintType(lzyArg) // ignore `Lazy` for the purposes of showing the implicit chain
    case tq"$typeclass[$arg]" => s"a $typeclass instance for type $arg"
    case _ => s"an implicit value of $typ"
  }
}
